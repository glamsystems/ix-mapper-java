package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// The generated documents as published: each file is named for its program and filed under
/// its environment, and every sentinel seat rewrites an absent optional to the proxy program.
final class BundledDocumentsTest {

  private static final PublicKey STATE = IxMapperFuzz.key(101);
  private static final PublicKey VAULT = IxMapperFuzz.key(102);
  private static final PublicKey SIGNER = IxMapperFuzz.key(103);
  private static final MappingContext CONTEXT = new MappingContext(STATE, VAULT, SIGNER, MapperConformanceTest::authorityOf);

  private static PublicKey resolve(final DynamicAccountName name, final MappingDocument document) {
    return switch (name) {
      case GLAM_STATE -> STATE;
      case GLAM_VAULT -> VAULT;
      case GLAM_SIGNER -> SIGNER;
      case INTEGRATION_AUTHORITY -> MapperConformanceTest.authorityOf(document.proxyProgramId());
    };
  }

  /// An instruction that satisfies every expectation of the entry, with the source program's
  /// id at the sentinel position and every other position present.
  private static Instruction synthesize(final MappingDocument document, final InstructionEntry.Mapped entry, final int sentinelSource) {
    final var accounts = new ArrayList<AccountMeta>();
    final var positions = entry.sourceAccounts();
    for (int i = 0; i < positions.size(); i++) {
      final var position = positions.get(i);
      final PublicKey key;
      if (i == sentinelSource) {
        key = document.programId();
      } else if (position.expect() instanceof Expectation.Dynamic dynamic) {
        key = resolve(dynamic.name(), document);
      } else if (position.expect() instanceof Expectation.Address address) {
        key = address.address();
      } else {
        key = IxMapperFuzz.key(110 + i);
      }
      accounts.add(AccountMeta.createMeta(key, position.writable(), position.signer()));
    }
    return Instruction.createInstruction(document.programId(), accounts, entry.discriminator().data());
  }

  @TestFactory
  Stream<DynamicTest> theBundledDocuments() {
    final var tests = new ArrayList<DynamicTest>();
    for (final var environment : List.of("production", "staging")) {
      final var directory = TestPaths.documents(environment);
      final List<java.nio.file.Path> files;
      try (final var paths = Files.list(directory)) {
        files = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      assertFalse(files.isEmpty(), "no documents under " + directory);
      for (final var file : files) {
        tests.add(DynamicTest.dynamicTest(environment + "/" + file.getFileName(), () -> {
          final var document = MappingDocuments.read(file);
          assertEquals(document.programId().toBase58() + ".json", file.getFileName().toString(), "named for its program");
          assertEquals(environment, document.environment(), "filed under its environment");
          final var mapper = InstructionMapper.createMapper(List.of(document));
          for (final var entry : document.instructions()) {
            if (!(entry instanceof InstructionEntry.Mapped mapped)) {
              continue;
            }
            for (final var seat : mapped.destinationAccounts()) {
              if (!(seat instanceof DestinationAccount.Source forwarded) || !forwarded.sentinel()) {
                continue;
              }
              final var instruction = synthesize(document, mapped, forwarded.source());
              final var result = mapper.map(instruction, CONTEXT);
              final var ok = assertInstanceOf(MapResult.Mapped.class, result, mapped.name() + " with the program id at position " + forwarded.source() + ": " + result);
              assertEquals(AccountMeta.createRead(document.proxyProgramId()), ok.instruction().accounts().get(seat.index()),
                  mapped.name() + ": seat " + seat.index() + " holds the proxy program, read-only and unsigned");
            }
          }
        }));
      }
    }
    return tests.stream();
  }
}
