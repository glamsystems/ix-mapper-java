plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

// IxMapperTest maps real instructions through every config in the untracked
// glam/ download, so a fresh clone (CI runs plain './gradlew check' via the
// sava-build reusable workflows) must fetch it before tests run. The script
// runs on every build: a glam/ already at the pinned commit is left alone,
// one at any other commit is replaced, so a checkout that predates the pin
// cannot test against older configs. ./syncMappings.sh <sha> moves the pin.
// -PglamMappingsDir=<absolute path> instead points the whole suite at another
// mappings root holding the same mapping-configs-v1/ and
// mapping-configs-v1-staging/ layout — the seam that lets regenerated configs
// face this validation before they are published upstream.
val downloadMappings by tasks.registering(Exec::class) {
  description = "Materializes the pinned ix-mapper-ts mapping configs under the untracked glam/ directory."
  val mappingsOverride = providers.gradleProperty("glamMappingsDir")
  workingDir = rootDir
  commandLine("./downloadMappings.sh")
  onlyIf { !mappingsOverride.isPresent }
}

tasks.withType<Test>().configureEach {
  dependsOn(downloadMappings)
  providers.gradleProperty("glamMappingsDir").orNull?.let { systemProperty("glam.mappings.dir", it) }
}

hardening {
  mutation.register("ixProxy") {
    // catch-all by exclusion, so a new class is mutated by default instead of
    // silently skipped; test sources share the recompiled root
    targetClasses = listOf("systems.glam.ix.proxy.*")
    excludedClasses = listOf(
      "systems.glam.ix.proxy.*Test*",
      "systems.glam.ix.proxy.*Fuzz*"
    )
    targetTests = "systems.glam.ix.proxy.*Test*"
  }
  fuzz.register("mappingConfig") {
    targetClass = "systems.glam.ix.proxy.MappingConfigFuzz"
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/mappingConfig")
    // config files are a few KB of JSON; headroom lets the mutator probe deep
    // nesting and long literals without clipping the real seeds
    maxLen = 65536
  }
  fuzz.register("ixMapper") {
    targetClass = "systems.glam.ix.proxy.IxMapperFuzz"
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/ixMapper")
    // 2 carve bytes + instruction data; real instructions are tens of bytes
    maxLen = 4096
  }
}
