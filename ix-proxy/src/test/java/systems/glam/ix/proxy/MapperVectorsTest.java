package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The mapping vectors under `test/data/vectors` of the TypeScript package, replayed the way
/// [MapperConformanceTest] replays the cases: generated from every entry of every bundled
/// document by the TypeScript mapper, one file of many vectors per document, they hold this
/// mapper to that one's output on every entry and prove agreement, not correctness; the cases
/// are the contract. They add no kill to the mutation suite over the cases and multiply its
/// run time, so the suite leaves this class out (`excludeTestClass` in the build script).
///
/// The vectors arrive with the monorepo sync that carries the first documents listing
/// supplied accounts. Before that sync the tracked tree has neither, and this factory is
/// skipped; once the synced documents list supplied accounts, a missing or empty vectors
/// directory fails.
final class MapperVectorsTest {

  private static boolean documentsListSuppliedAccounts() {
    return Stream.of("production", "staging")
        .flatMap(environment -> MappingDocuments.readDirectory(TestPaths.documents(environment)).stream())
        .flatMap(document -> document.instructions().stream())
        .anyMatch(entry -> entry instanceof InstructionEntry.Mapped mapped && !mapped.suppliedAccounts().isEmpty());
  }

  @TestFactory
  Stream<DynamicTest> theMappingVectors() {
    final var vectors = TestPaths.vectors();
    assumeTrue(Files.isDirectory(vectors) || documentsListSuppliedAccounts(),
        "no vectors under " + vectors + " and no synced document lists supplied accounts yet");
    assertTrue(Files.isDirectory(vectors), "the synced documents list supplied accounts, so the vectors must be synced too: " + vectors);
    final var files = Stream.of("production", "staging")
        .flatMap(environment -> MapperConformanceTest.jsonFiles(vectors.resolve(environment)).stream())
        .toList();
    assertFalse(files.isEmpty(), "no vectors under " + vectors);
    return files.stream().flatMap(file -> {
      final var fileName = file.getFileName().toString();
      return Json.array(MapperConformanceTest.readJson(file)).stream().map(vector -> {
        // the same display name recurs across documents, so the file leads it
        final var named = new java.util.LinkedHashMap<>(Json.object(vector));
        named.put("name", fileName + ": " + named.get("name"));
        return MapperConformanceTest.test(named, fileName);
      });
    });
  }
}
