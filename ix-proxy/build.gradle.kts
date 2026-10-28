plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

// MapperConformanceTest maps every case under test/data/cases of the TypeScript mapper
// package, and the environment-backed cases read the generated documents under
// src/generated/mapping, both from the tracked ix-mapper-ts/ directory, which the GLAM
// monorepo's sync workflow writes in the package's layout (ix-mapper-ts/README.md; the
// first copy was made by hand from glamsystems/ix-mapper-ts).
// -PglamMappingsDir=<path> (resolved against the root project) instead points the whole
// suite at another root with the same layout (a checkout of packages/glam/ix-mapper-ts in
// the monorepo) — the seam that lets regenerated documents and new cases face this
// validation before they are synced.
//
// The mapping content (documents, conformance cases, mapping vectors) is a test input: a
// synced change or a regenerated override tree must re-run the suite rather than serve a
// cached result.
val mappingsRoot: File = providers.gradleProperty("glamMappingsDir").map { rootProject.file(it) }.getOrElse(rootDir.resolve("ix-mapper-ts"))
val mappingsInputs = fileTree(mappingsRoot) { include("src/generated/mapping/**", "test/data/cases/**", "test/data/vectors/**") }

tasks.withType<Test>().configureEach {
  inputs.files(mappingsInputs).withPropertyName("glamMappings").withPathSensitivity(PathSensitivity.RELATIVE)
  providers.gradleProperty("glamMappingsDir").orNull?.let { systemProperty("glam.mappings.dir", mappingsRoot.absolutePath) }
}

// Git-ignored scratch drivers (Integ.java and the like) live here, outside `main` and `test`.
// Nothing in `check` compiles or runs them: `compileScratchJava` does the first on request and the
// suite's test task is disabled; a driver is launched from the IDE or `java` with the suite's
// class path. The AGENTS.md hardening block says why they live apart.
testing {
  suites {
    register<JvmTestSuite>("scratch") {
      dependencies {
        implementation(project())
        implementation("org.junit.jupiter:junit-jupiter-api")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
      targets.all {
        testTask.configure {
          enabled = false
        }
      }
    }
  }
}
configurations.named("scratchImplementation") {
  withDependencies {
    removeIf { it.group == "org.junit.jupiter" && it.name == "junit-jupiter" }
  }
}

// On the class path, not the module path: the module-testing plugin would otherwise compile the
// suite into the main module as a whitebox test, where JUnit is not visible.
javaModuleTesting {
  classpath(testing.suites.getByName("scratch"))
}

// The suite declares its JUnit dependency itself and has no module-info for the scope check to
// read; the dependency analysis would otherwise report it.
dependencyAnalysis {
  issues {
    ignoreSourceSet("scratch")
  }
}

hardening {
  mutation.register("ixProxy") {
    // the naked-receiver mutator fires here (the reader's fluent skip(), the path helpers),
    // so it is on; see config/pitest/README.md for the trial
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    // catch-all by exclusion, so a new class is mutated by default instead of
    // silently skipped; test sources share the recompiled root
    targetClasses = listOf("systems.glam.ix.proxy.*")
    excludedClasses = listOf(
      "systems.glam.ix.proxy.*Test*",
      "systems.glam.ix.proxy.*Fuzz*",
      // test support in the shared root: the JSON tree the suites patch documents with (and
      // its switch-map class), and where they find the tracked package
      "systems.glam.ix.proxy.Json",
      "systems.glam.ix.proxy.Json$*",
      "systems.glam.ix.proxy.TestPaths"
    )
    targetTests = "systems.glam.ix.proxy.*Test*"
    // the vectors replay every bundled entry against the TypeScript mapper's output: no kill the
    // cases do not already make, and 2.4 times the run; `check` still runs them
    excludeTestClass("systems.glam.ix.proxy.MapperVectorsTest", "no kill over the conformance cases; multiplies the run time")
    // the override reaches PIT's minion as one JVM argument; the plugin refuses a path it
    // cannot write into the minion's argument file when a pitest task builds its command line
    providers.gradleProperty("glamMappingsDir").orNull?.let { minionJvmArgs.add("-Dglam.mappings.dir=" + rootProject.file(it).absolutePath) }
  }
  fuzz.register("mappingConfig") {
    targetClass = "systems.glam.ix.proxy.MappingConfigFuzz"
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/mappingConfig")
    // the seeded documents run to 59 KB; headroom lets the mutator probe deep nesting and
    // long literals without clipping them
    maxLen = 131072
  }
  fuzz.register("ixMapper") {
    targetClass = "systems.glam.ix.proxy.IxMapperFuzz"
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/ixMapper")
    // 6 carve bytes (program, count and rotation, flags, sentinel, span) + the buffer the
    // span reads; real instructions are tens of bytes
    maxLen = 4096
  }
}
