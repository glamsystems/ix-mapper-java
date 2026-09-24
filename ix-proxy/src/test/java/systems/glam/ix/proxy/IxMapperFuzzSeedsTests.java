package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import software.sava.core.accounts.meta.AccountMeta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Each committed fuzz seed reaches the outcome its name promises, so a seed pins a path and
/// not merely a byte pattern the harness happens to tolerate.
final class IxMapperFuzzSeedsTests {

  private static final Path SEEDS = Path.of("src/test/resources/fuzz/ixMapper");

  /// The outcome by seed name: a result kind, and for a refusal its reason.
  private static final Map<String, Object> EXPECTED = Map.ofEntries(
      Map.entry("a-full-every-position", MapResult.Mapped.class),
      Map.entry("a-full-omitted-trailing", MapResult.Mapped.class),
      Map.entry("a-full-sentinel-rewrite", MapResult.Mapped.class),
      Map.entry("a-full-remaining-accounts", MapResult.Mapped.class),
      Map.entry("a-full-too-few", UnsupportedReason.ACCOUNT_COUNT),
      Map.entry("a-full-wrong-owner", UnsupportedReason.ACCOUNT_EXPECTATION),
      Map.entry("a-full-signing-thing", UnsupportedReason.ACCOUNT_PRIVILEGE),
      Map.entry("a-passthrough", MapResult.Passthrough.class),
      Map.entry("a-refused", UnsupportedReason.REFUSED_INSTRUCTION),
      Map.entry("a-unknown-discriminator", UnsupportedReason.UNKNOWN_INSTRUCTION),
      Map.entry("a-empty-data", UnsupportedReason.UNKNOWN_INSTRUCTION),
      Map.entry("a-span-past-the-buffer", UnsupportedReason.UNREADABLE_INSTRUCTION),
      Map.entry("b-strict-exact", MapResult.Mapped.class),
      Map.entry("b-strict-extra-account", UnsupportedReason.REMAINING_ACCOUNTS),
      Map.entry("b-strict-short-discriminator", UnsupportedReason.UNKNOWN_INSTRUCTION),
      Map.entry("unknown-program", MapResult.Passthrough.class)
  );

  @TestFactory
  Stream<DynamicTest> everySeedReachesItsOutcome() throws IOException {
    final var files = Files.list(SEEDS).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
    assertEquals(EXPECTED.keySet(), files.stream().map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
        "every seed has an expected outcome and every expected outcome a seed");
    return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      final var instruction = IxMapperFuzz.carve(bytes);
      assertNotNull(instruction, "the seed carves an instruction");
      final var result = IxMapperFuzz.MAPPER.map(instruction, IxMapperFuzz.CONTEXT);
      final var expected = EXPECTED.get(file.getFileName().toString());
      if (expected instanceof UnsupportedReason reason) {
        assertEquals(reason, assertInstanceOf(MapResult.Unsupported.class, result).reason());
      } else {
        assertInstanceOf((Class<?>) expected, result);
      }
      if (result instanceof MapResult.Mapped mapped) {
        IxMapperFuzz.check(instruction, mapped);
        if (file.getFileName().toString().equals("a-full-sentinel-rewrite")) {
          assertEquals(AccountMeta.createRead(IxMapperFuzz.PROXY), mapped.instruction().accounts().get(6), "the sentinel seat holds the proxy program");
        }
      }
    }));
  }
}
