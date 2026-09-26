package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Each committed `mappingConfig` seed reaches the outcome its name promises: the parser
/// admits it with the entries it holds, or refuses it with the message of the rule it
/// breaks. The replay test only shows that no seed escapes the harness.
final class MappingConfigFuzzSeedsTests {

  private static final Path SEEDS = Path.of("src/test/resources/fuzz/mappingConfig");

  /// A seed's outcome: the entries of an admitted document, or the whole refusal message.
  private record Outcome(int entries, String refusal) {
  }

  private static Outcome admits(final int entries) {
    return new Outcome(entries, null);
  }

  private static Outcome refused(final String message) {
    return new Outcome(-1, message);
  }

  private static final String FIRST_ENTRY = "fuzz 11111111111111111111111111111111 instructions[0]";

  private static final Map<String, Outcome> EXPECTED = Map.ofEntries(
      Map.entry("system-production.json", admits(14)),
      Map.entry("kamino-production.json", admits(66)),
      Map.entry("marinade-staging.json", admits(29)),
      Map.entry("no-instructions", admits(0)),
      Map.entry("empty-object", refused("fuzz: schema_version undefined is not 1")),
      Map.entry("malformed-utf8-environment", refused("fuzz: is not JSON: malformed UTF-8 at offset 36")),
      Map.entry("supplied-accounts", admits(2)),
      Map.entry("supplied-optional-false", refused(FIRST_ENTRY + " supplied_accounts[2]: optional must be true when present")),
      Map.entry("supplied-of-out-of-range", refused(FIRST_ENTRY
          + ": supplied_accounts[0] names source position 3, which is out of range of 3")),
      Map.entry("supplied-required-after-optional", refused(FIRST_ENTRY
          + ": supplied_accounts[1] is required after an optional one; optional accounts trail")),
      Map.entry("supplied-on-passthrough", refused(FIRST_ENTRY + ": a passthrough entry carries no \"supplied_accounts\"")),
      Map.entry("supplied-of-omittable", refused(FIRST_ENTRY
          + ": supplied_accounts[0] names source position 3, which a client may leave out; an absent run would shift it")),
      Map.entry("supplied-behind-omittable-seat", refused(FIRST_ENTRY
          + ": supplied_accounts follow seat 5, which a client may leave out; an absent one would shift them")),
      Map.entry("supplied-of-program-id", refused(FIRST_ENTRY
          + ": supplied_accounts[0] names source position 2, which a client may pass as the program id; an absent optional names no account"))
  );

  @TestFactory
  Stream<DynamicTest> everySeedReachesItsOutcome() throws IOException {
    final var files = Files.list(SEEDS).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
    assertEquals(EXPECTED.keySet(), files.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet()),
        "every seed has an expected outcome and every expected outcome a seed");
    return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      MappingConfigFuzz.fuzzerTestOneInput(bytes);
      final var expected = EXPECTED.get(file.getFileName().toString());
      if (expected.refusal() == null) {
        assertEquals(expected.entries(), MappingDocumentParser.parse(bytes, "fuzz").instructions().size());
      } else {
        final var refusal = assertThrows(MappingDocumentException.class, () -> MappingDocumentParser.parse(bytes, "fuzz"));
        assertEquals(expected.refusal(), refusal.getMessage());
      }
    }));
  }
}
