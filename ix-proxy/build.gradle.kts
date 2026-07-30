plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

// IxMapperTest maps real instructions through every config in the untracked
// glam/ download, so a fresh clone (CI runs plain './gradlew check' via the
// sava-build reusable workflows) must fetch it before tests run. Once the
// directory exists this is a no-op; use ./syncMappings.sh to pull updates.
val downloadMappings by tasks.registering(Exec::class) {
  description = "Clones the ix-mapper-ts mapping configs into the untracked glam/ directory."
  val glamDir = rootDir.resolve("glam")
  workingDir = rootDir
  commandLine("./downloadMappings.sh")
  onlyIf { !glamDir.isDirectory }
}

tasks.withType<Test>().configureEach {
  dependsOn(downloadMappings)
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
