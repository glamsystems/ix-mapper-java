package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The conformance set every mapper of the document passes: the cases under
/// `test/data/cases` of the TypeScript package, each an instruction, a context and the result
/// the rules give for it, compared whole, message included. A case names its own documents or
/// an environment whose generated documents it maps against. [MapperVectorsTest] replays the
/// generated vectors the same way.
final class MapperConformanceTest {

  /// The oracle names an integration authority by the proxy program it belongs to, exactly as
  /// the TypeScript suite does: `Auth` and the proxy address past its first four characters,
  /// capped at 44 characters.
  static PublicKey authorityOf(final PublicKey proxyProgram) {
    final var proxy = proxyProgram.toBase58();
    final var name = "Auth" + proxy.substring(4);
    return PublicKey.fromBase58Encoded(name.length() > 44 ? name.substring(0, 44) : name);
  }

  static Instruction toInstruction(final Map<String, Object> value) {
    final var program = PublicKey.fromBase58Encoded((String) value.get("programAddress"));
    final var accounts = new ArrayList<AccountMeta>();
    for (final var element : Json.array(value.get("accounts"))) {
      final var account = Json.object(element);
      accounts.add(AccountMeta.createMeta(
          PublicKey.fromBase58Encoded((String) account.get("address")),
          (Boolean) account.get("writable"),
          (Boolean) account.get("signer")
      ));
    }
    final var bytes = Json.array(value.get("data"));
    final byte[] data = new byte[bytes.size()];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) ((Long) bytes.get(i)).intValue();
    }
    return Instruction.createInstruction(program, accounts, data);
  }

  /// The context a case describes. Its supplier serves the entries the case spells out, each
  /// with the request the mapper must make for it (see [#assertRequests]) and what the
  /// supplier does: answers with a list (a null element kept) or null, or throws with a
  /// message. Every request made lands in `asked`.
  static MappingContext toContext(final Map<String, Object> value, final List<SuppliedAccountsRequest> asked) {
    final Function<PublicKey, PublicKey> authority = Boolean.TRUE.equals(value.get("integrationAuthority"))
        ? MapperConformanceTest::authorityOf
        : null;
    final var suppliedRaw = value.get("suppliedAccounts");
    final Function<SuppliedAccountsRequest, List<PublicKey>> supplied;
    if (suppliedRaw == null) {
      supplied = null;
    } else {
      final var byEntry = Json.object(suppliedRaw);
      supplied = request -> {
        asked.add(request);
        final var spec = byEntry.get(request.source());
        if (spec == null) {
          return null;
        }
        final var throwsMessage = Json.object(spec).get("throws");
        if (throwsMessage != null) {
          throw new IllegalStateException((String) throwsMessage);
        }
        final var answer = Json.object(spec).get("answer");
        if (answer == null) {
          return null;
        }
        final var addresses = new ArrayList<PublicKey>();
        for (final var element : Json.array(answer)) {
          addresses.add(element == null ? null : PublicKey.fromBase58Encoded((String) element));
        }
        return addresses;
      };
    }
    return new MappingContext(
        PublicKey.fromBase58Encoded((String) value.get("glamState")),
        PublicKey.fromBase58Encoded((String) value.get("glamVault")),
        PublicKey.fromBase58Encoded((String) value.get("glamSigner")),
        authority,
        supplied
    );
  }

  /// The mapper asked once per entry the case serves, for the roles the case spells out with
  /// the addresses at their `of` positions and, for an account at an account index, its
  /// derivation resolved ([#derivation]; a role that spells none has none), and handed the
  /// supplier the instruction itself.
  static void assertRequests(final Map<String, Object> value,
                             final Instruction instruction,
                             final List<SuppliedAccountsRequest> asked) {
    final var suppliedRaw = value.get("suppliedAccounts");
    if (suppliedRaw == null) {
      return;
    }
    for (final var entry : Json.object(suppliedRaw).entrySet()) {
      final var requests = asked.stream().filter(request -> request.source().equals(entry.getKey())).toList();
      assertEquals(1, requests.size(), entry.getKey() + " is asked once");
      final var request = requests.getFirst();
      assertSame(instruction, request.instruction());
      final var expected = Json.object(Json.object(entry.getValue()).get("request"));
      assertEquals(PublicKey.fromBase58Encoded((String) expected.get("proxyProgram")), request.proxyProgram());
      assertEquals(PublicKey.fromBase58Encoded((String) expected.get("program")), request.program());
      assertEquals(expected.get("handler"), request.handler());
      final var roles = new ArrayList<SuppliedAccountsRequest.Role>();
      for (final var element : Json.array(expected.get("roles"))) {
        final var role = Json.object(element);
        final var of = new ArrayList<PublicKey>();
        for (final var address : Json.array(role.get("of"))) {
          of.add(PublicKey.fromBase58Encoded((String) address));
        }
        final var derivation = role.get("derivation");
        roles.add(new SuppliedAccountsRequest.Role((String) role.get("role"), of, (Boolean) role.get("optional"),
            derivation == null ? null : derivation(Json.object(derivation))));
      }
      assertEquals(roles, request.roles());
    }
    // and nothing else was asked: an entry without supplied accounts leaves the supplier alone
    assertEquals(Json.object(suppliedRaw).size(), asked.size(), "every ask is declared");
  }

  /// A role's derivation as a case spells it, resolved: the program, then each seed by its
  /// kind with the bytes, the address or the path it carries.
  static SuppliedAccountsRequest.Derivation derivation(final Map<String, Object> value) {
    final var seeds = new ArrayList<SuppliedAccountsRequest.Seed>();
    for (final var element : Json.array(value.get("seeds"))) {
      final var seed = Json.object(element);
      seeds.add(switch ((String) seed.get("kind")) {
        case "const" -> {
          final var bytes = Json.array(seed.get("value"));
          final byte[] constant = new byte[bytes.size()];
          for (int i = 0; i < constant.length; i++) {
            constant[i] = (byte) ((Long) bytes.get(i)).intValue();
          }
          yield new SuppliedAccountsRequest.Const(constant);
        }
        case "account" -> new SuppliedAccountsRequest.Account(PublicKey.fromBase58Encoded((String) seed.get("address")));
        case "arg" -> new SuppliedAccountsRequest.Arg((String) seed.get("path"));
        default -> throw new IllegalArgumentException("a case's seed of unknown kind " + seed.get("kind"));
      });
    }
    return new SuppliedAccountsRequest.Derivation(PublicKey.fromBase58Encoded((String) value.get("program")), seeds);
  }

  /// The request comparison reads a role's derivation in the resolved shape a case spells (the
  /// program; a constant's bytes, an account's address, an argument's path) and takes an
  /// absent one as none: a request that carries the same passes, and one whose derivation is
  /// missing or differs in a seed fails.
  @Test
  void theRequestComparisonReadsADerivation() {
    final var proxy = PublicKey.fromBase58Encoded("Proxy11111111111111111111111111111111111111");
    final var program = PublicKey.fromBase58Encoded("Src1111111111111111111111111111111111111111");
    final var state = PublicKey.fromBase58Encoded("State11111111111111111111111111111111111111");
    final var context = Json.object(Json.read("""
        {
          "suppliedAccounts": {
            "route": {
              "request": {
                "proxyProgram": "%1$s", "program": "%2$s", "handler": "proxy_route",
                "roles": [
                  {
                    "role": "routes", "of": [], "optional": false,
                    "derivation": {
                      "program": "%1$s",
                      "seeds": [
                        { "kind": "const", "value": [114, 255] },
                        { "kind": "account", "address": "%3$s" },
                        { "kind": "arg", "path": "params.protocol" }
                      ]
                    }
                  },
                  { "role": "oracle", "of": [], "optional": true }
                ]
              },
              "answer": []
            }
          }
        }
        """.formatted(proxy.toBase58(), program.toBase58(), state.toBase58())));
    final var instruction = Instruction.createInstruction(program, List.of(), new byte[]{6});
    final Function<SuppliedAccountsRequest.Derivation, List<SuppliedAccountsRequest>> asked = derivation -> List.of(
        new SuppliedAccountsRequest(proxy, program, "route", "proxy_route", List.of(
            new SuppliedAccountsRequest.Role("routes", List.of(), false, derivation),
            new SuppliedAccountsRequest.Role("oracle", List.of(), true)
        ), instruction));
    assertRequests(context, instruction, asked.apply(new SuppliedAccountsRequest.Derivation(proxy, List.of(
        new SuppliedAccountsRequest.Const(new byte[]{114, (byte) 255}),
        new SuppliedAccountsRequest.Account(state),
        new SuppliedAccountsRequest.Arg("params.protocol")
    ))));
    assertThrows(AssertionError.class, () -> assertRequests(context, instruction, asked.apply(null)));
    assertThrows(AssertionError.class, () -> assertRequests(context, instruction, asked.apply(new SuppliedAccountsRequest.Derivation(proxy, List.of(
        new SuppliedAccountsRequest.Const(new byte[]{114, (byte) 254}),
        new SuppliedAccountsRequest.Account(state),
        new SuppliedAccountsRequest.Arg("params.protocol")
    )))));
    assertThrows(AssertionError.class, () -> assertRequests(context, instruction, asked.apply(new SuppliedAccountsRequest.Derivation(proxy, List.of(
        new SuppliedAccountsRequest.Const(new byte[]{114, (byte) 255}),
        new SuppliedAccountsRequest.Account(proxy),
        new SuppliedAccountsRequest.Arg("params.protocol")
    )))));
  }

  /// A result as a JSON tree in the TypeScript suite's comparable shape: absent fields are
  /// absent, addresses are strings, bytes are numbers.
  static Map<String, Object> toComparable(final MapResult result) {
    final var map = new LinkedHashMap<String, Object>();
    switch (result) {
      case MapResult.Mapped mapped -> {
        map.put("kind", "mapped");
        map.put("instruction", instructionTree(mapped.instruction()));
        map.put("program", mapped.program().toBase58());
        map.put("source", mapped.source());
        map.put("handler", mapped.handler());
      }
      case MapResult.Passthrough passthrough -> {
        map.put("kind", "passthrough");
        map.put("instruction", instructionTree(passthrough.instruction()));
        map.put("program", passthrough.program().toBase58());
        if (passthrough.source() != null) {
          map.put("source", passthrough.source());
        }
        map.put("reason", passthrough.reason());
      }
      case MapResult.Unsupported unsupported -> {
        map.put("kind", "unsupported");
        map.put("program", unsupported.program().toBase58());
        if (unsupported.source() != null) {
          map.put("source", unsupported.source());
        }
        map.put("reason", unsupported.reason().jsonName());
        map.put("message", unsupported.message());
      }
    }
    return map;
  }

  static Map<String, Object> instructionTree(final Instruction instruction) {
    final var map = new LinkedHashMap<String, Object>();
    map.put("programAddress", instruction.programId().publicKey().toBase58());
    final var accounts = new ArrayList<>();
    for (final var account : instruction.accounts()) {
      final var meta = new LinkedHashMap<String, Object>();
      meta.put("address", account.publicKey().toBase58());
      meta.put("writable", account.write());
      meta.put("signer", account.signer());
      accounts.add(meta);
    }
    map.put("accounts", accounts);
    final var data = new ArrayList<Object>(instruction.len());
    for (int i = 0; i < instruction.len(); i++) {
      data.add((long) (instruction.data()[instruction.offset() + i] & 0xff));
    }
    map.put("data", data);
    return map;
  }

  static InstructionMapper mapperFor(final Map<String, Object> testCase) {
    final var environment = testCase.get("environment");
    if (environment == null) {
      final var documents = new ArrayList<MappingDocument>();
      final var raw = testCase.get("documents");
      int i = 0;
      for (final var document : raw == null ? List.of() : Json.array(raw)) {
        documents.add(MappingDocumentParser.parse(Json.write(document), "documents[" + i + "]"));
        ++i;
      }
      return InstructionMapper.createMapper(documents);
    }
    return InstructionMapper.createMapper(MappingDocuments.readDirectory(TestPaths.documents((String) environment)));
  }

  static List<Path> jsonFiles(final Path dir) {
    try (final var paths = Files.list(dir)) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Object readJson(final Path file) {
    try {
      return Json.read(Files.readAllBytes(file));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> theMappingCases() {
    final var files = jsonFiles(TestPaths.cases());
    assertFalse(files.isEmpty(), "no cases under " + TestPaths.cases());
    return files.stream().map(file -> test(Json.object(readJson(file)), file.getFileName().toString()));
  }

  static DynamicTest test(final Map<String, Object> testCase, final String fileName) {
      return DynamicTest.dynamicTest(testCase.get("name") + ": " + testCase.get("note"), () -> {
        final var mapper = mapperFor(testCase);
        final var instruction = toInstruction(Json.object(testCase.get("instruction")));
        final var context = Json.object(testCase.get("context"));
        final var asked = new ArrayList<SuppliedAccountsRequest>();
        final var result = mapper.map(instruction, toContext(context, asked));
        assertEquals(testCase.get("expected"), toComparable(result), fileName);
        assertRequests(context, instruction, asked);
      });
  }
}
