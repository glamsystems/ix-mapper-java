package systems.glam.ix.proxy;

import java.nio.file.Path;

/// Where the suites read the generated documents and the conformance cases: the package
/// layout of `packages/glam/ix-mapper-ts` in the GLAM monorepo, as the public
/// `glamsystems/ix-mapper-ts` repository mirrors it. The root is the tracked `../ix-mapper-ts`
/// directory (the monorepo's sync workflow writes it; the first copy was made by hand from
/// that repository) by default, or the directory named by
/// the `glam.mappings.dir` system property (wired from the `glamMappingsDir` Gradle
/// property), so a checkout of the package itself can face this validation before it is
/// synced.
final class TestPaths {

  private TestPaths() {
  }

  static Path mappingsRoot() {
    return Path.of(System.getProperty("glam.mappings.dir", "../ix-mapper-ts"));
  }

  /// The documents of an environment, `src/generated/mapping/<environment>/<program>.json`.
  static Path documents(final String environment) {
    return mappingsRoot().resolve("src").resolve("generated").resolve("mapping").resolve(environment);
  }

  /// The conformance cases, `test/data/cases/*.json`.
  static Path cases() {
    return mappingsRoot().resolve("test").resolve("data").resolve("cases");
  }
}
