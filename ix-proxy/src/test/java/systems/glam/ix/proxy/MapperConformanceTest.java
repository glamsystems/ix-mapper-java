package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
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

/// The conformance set every mapper of the document passes: the cases under
/// `test/data/cases` of the TypeScript package, each an instruction, a context and the result
/// the rules give for it, compared whole, message included. A case names its own documents or
/// an environment whose generated documents it maps against.
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

  static MappingContext toContext(final Map<String, Object> value) {
    final Function<PublicKey, PublicKey> authority = Boolean.TRUE.equals(value.get("integrationAuthority"))
        ? MapperConformanceTest::authorityOf
        : null;
    return new MappingContext(
        PublicKey.fromBase58Encoded((String) value.get("glamState")),
        PublicKey.fromBase58Encoded((String) value.get("glamVault")),
        PublicKey.fromBase58Encoded((String) value.get("glamSigner")),
        authority
    );
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

  @TestFactory
  Stream<DynamicTest> theMappingCases() {
    final List<Path> files;
    try (final var paths = Files.list(TestPaths.cases())) {
      files = paths
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    assertFalse(files.isEmpty(), "no cases under " + TestPaths.cases());
    return files.stream().map(file -> {
      final Map<String, Object> testCase;
      try {
        testCase = Json.object(Json.read(Files.readAllBytes(file)));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      return DynamicTest.dynamicTest(testCase.get("name") + ": " + testCase.get("note"), () -> {
        final var mapper = mapperFor(testCase);
        final var result = mapper.map(
            toInstruction(Json.object(testCase.get("instruction"))),
            toContext(Json.object(testCase.get("context")))
        );
        assertEquals(testCase.get("expected"), toComparable(result), file.getFileName().toString());
      });
    });
  }
}
