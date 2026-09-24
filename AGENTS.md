# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

Everything here is portable — true of any checkout. Machine-specific context
(local sibling checkouts, credentials, observed timings) belongs in an
untracked `AGENTS.local.md`, not here.

## What this repository is

The GLAM instruction mapper: a small, dependency-light Java library that
rewrites arbitrary Solana instructions into their GLAM proxy-program
equivalents. Mapping rules are data, not code — one mapping document per
source program and environment, generated in the GLAM monorepo
(`packages/glam/ix-mapper-ts`) and published through
`glamsystems/ix-mapper-ts` — and this library parses them
(`MappingDocumentParser`), holds them (`MappingDocument`, `InstructionEntry`,
`SourceAccount`, `DestinationAccount`) and remaps discriminators and account
lists at runtime (`InstructionMapper`, `MapResult`, `MappingContext`).
The TypeScript package in that repository is the other mapper of the same
documents; the two share a schema, a rule set and the contract's refusal
messages (what this mapper refuses on its own is listed on the tracking
issue), and the conformance cases under that package's `test/data/cases`
are the contract both must pass. `glam-sdk-java` is the primary consumer.

### Layout

- `ix-proxy/` (JPMS module `systems.glam.ix_proxy`, package
  `systems.glam.ix.proxy`) — the whole library. The document model (sealed
  `InstructionEntry`, `DestinationAccount`, `Expectation`; records
  `MappingDocument`, `SourceAccount`, `Handler`, `Provenance`), the parser
  (`MappingDocumentParser`, `MappingDocuments` for files), the mapper
  (`InstructionMapper` over `DocumentMapper`, `MappingContext`, sealed
  `MapResult`, `UnsupportedReason`, `UnsupportedInstructionException`) and
  `MappingDocumentException` for a document that does not admit.
- `ix-mapper-ts/` — the generated documents
  (`src/generated/mapping/{production,staging}`) and the conformance cases
  (`test/data/cases`) of the TypeScript package, in its layout, written by
  the GLAM monorepo's public-sync workflow and never by hand
  (`ix-mapper-ts/README.md`; the first copy was made by hand from
  ix-mapper-ts 16320bf). The trade-off: an upstream document or case change
  is tested here only once its sync commit lands on `main`, and a sync
  commit is a test-only change that lands without a mutation run, so the
  next code change's `pitestIxProxy -PnoMutationHistory` run is where a case
  it changed shows its effect. The hardening plugin's evidence manifest does
  not fingerprint the tracked tree, nor an override tree edited in place, so
  after either changes run `pitestIxProxy -PnoMutationHistory` before
  trusting a standalone `pitestIxProxyVerify`. `-PglamMappingsDir=<path>`
  (resolved against the repository root) points the suite, and PIT's minion,
  at a local checkout of the package instead, for documents and cases that are
  not synced yet; the mapping content is a declared test input either way. A
  PIT run refuses a path the hardening plugin cannot write into the minion's
  argument file.

## Build & test

- Java 25, full JPMS, Gradle wrapper. Build logic comes from the external
  `software.sava.build` convention plugin (separate repo `sava-build`; version
  pinned in `settings.gradle.kts`). No root `build.gradle.kts`; shared
  coordinates and the Solana BOM version live in `gradle/sava.properties`.
- Resolving dependencies requires GitHub Packages credentials
  (`savaGithubPackagesUsername` / `savaGithubPackagesPassword` in
  `~/.gradle/gradle.properties`).
- `./gradlew check` — full build + tests. CI (reusable workflows from
  sava-build) runs exactly this; keep it green.
- To build against an unpublished sava-build change, publish sava-build's
  local test repo and run with
  `-PsavaBuildLocalRepo=../sava-build/build/sava-test-repo` — the property
  lives on the CLI or in `~/.gradle/gradle.properties`, never in the file.
  The plugin announces local-repo resolution at the end of every such build;
  a build that prints no notice did NOT run `0.0.0-test`.
- Commits follow Conventional Commits (`feat: ...`, `fix: ...`);
  release-please cuts releases from them. Don't hand-edit versions or
  `CHANGELOG.md`. Tagged releases publish to GitHub Packages only
  (`publish-gh.yml`, sava-build's reusable publish workflow).

## This is a published library

Consumers (notably `glam-sdk-java`) depend on current behaviour. Pin a
surprising behaviour with a test and report it before changing it; additive
fixes are fine, changing what an existing signature returns is not, without
the user's say-so.

## Testing conventions

- JUnit 5, built-in `Assertions`, package-private `final class` tests in the
  **same package** as the code under test (JPMS whitebox patching is wired by
  the build plugin) — reach for package-private access, not reflection.
- `MapperConformanceTest` runs every case under the package's
  `test/data/cases`: an instruction, a context and the whole expected result,
  message included, against the case's own documents or an environment's
  generated set. A rule of the shared contract lands as a case there (in the
  GLAM monorepo), so both mappers stay one contract; a rule the case format
  cannot express (what this parser refuses beyond the contract) is tested
  here and listed on the tracking issue. `MappingDocumentParserTests` carries
  the contract's refusal table and the rows this parser adds. Tests never hit
  the network; the build's only network use is dependency and JDK
  resolution.
- Randomized tests use fixed seeds; nothing sleeps. Time-dependent code takes
  a clock seam, never the wall clock — give test clocks a non-zero origin.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

The `ix-proxy` module registers the PIT suite `pitestIxProxy` via the
`software.sava.build.feature.hardening` plugin, targeting
`systems.glam.ix.proxy.*` by wildcard with test sources and the test
helpers excluded, so a new class is mutated by default. The run diffs
unkilled mutants against the accepted baseline in `ix-proxy/config/pitest/`
and fails on anything new. The record was re-seeded for the document mapper;
`config/pitest/README.md` holds the triage history, the family arguments and
the mutator-trial status.

Two fuzz targets, with seed corpora under `ix-proxy/src/test/resources/fuzz/`
replayed inside `check` by generated `*FuzzSeedReplayTest`s:
`fuzzMappingConfig` (`MappingConfigFuzz`) drives the full startup path for
external document JSON — `MappingDocumentParser.parse` through
`InstructionMapper.createMapper`; `fuzzIxMapper` (`IxMapperFuzz`) carves
arbitrary bytes into instructions and drives `InstructionMapper.map` — the
path that faces user-submitted transactions — asserting the program, the
data and every seat on every mapped result. Register new harnesses
in the `hardening` block with `targetClass` AND `seedCorpus` (both required —
a missing `seedCorpus` silently skips the replay test).

Full fuzz campaigns are deliberately local-only. There is no GitHub Actions
fuzz workflow because its CI runner cost is not justified for this library;
run `:ix-proxy:fuzzAll` locally with explicit `-PmaxFuzzTime` and
`-PmaxParallelFuzzTargets` budgets. CI still replays every committed seed
corpus inside `check`.

The full policy is sava-build's `HARDENING.md`; the process contract for
changes here:

<!-- The bounded block below is a copy of the agent-instructions template from
     sava-build's HARDENING.md, as `hardeningAgentTemplate` prints it for the
     installed version. Nothing checks it: when the printed block changes,
     re-take the copy and ACT on each changed bullet (a new bullet may need
     code, not prose). Repository-specific facts live after the block, never
     inside it. -->
<!-- hardening-template block:start -->
- Iterate with the module's `test` task. Before handoff, run each `pitest<Suite>`
  whose mutated code the change can reach, including suites in dependent modules,
  and `mutationOwnershipAudit` when production classes or target/exclusion rules
  change. `hardeningCertify` (or `:hardeningCertifyAll`) is the pre-release check
  this repo's notes assign an owner to, not the inner loop.
- Iterate on one cluster with `-PmutateOnly=<class-glob>`. Before any record
  decision, re-run unscoped with `-PnoMutationHistory`: a `[history]` report cannot
  support adding, removing, or relabelling records.
- An unkilled mutant has three outcomes: kill it with a test that asserts the
  property it breaks, refactor it out of existence, or accept it with a written
  reason in `config/pitest/README.md` and a family label on the row. Refreshes seed
  rows `# untriaged`; triage replaces that label. Never accept a `NO_COVERAGE`
  mutant as equivalent; it is an untested line.
- A mutant is a question, not a specification. State the intended property and an
  oracle independent of the implementation before writing the killing test. If they
  contradict current behaviour, prove the bug with a failing regression test first,
  then fix production; never lock a bug in with a passing assertion.
- Write records only through the installed writer tasks: `BaselineUnion` adds
  reviewed rows, `BaselineRetag` refreshes `# line` metadata, `BaselinePrune` deletes
  only after two matching fresh history-free previews, `BaselineUpdate` is for a
  first seed or a reviewed complete rewrite, and `pitest<Suite>BaselineRebase`
  follows a PIT, PIT-plugin/tool-artifact, ArcMutate-base, or certificate change.
  Never hand-edit baseline
  rows or provenance stamps.
- Baseline keys are line-less (`class,method,mutator,STATUS`); `# line` tags are
  review metadata. Identical rows are sibling mutants and the comparison is a
  multiset: never hand-dedupe.
- A new `TIMED_OUT` mutant is a reviewer stop, never detection. Record it in
  `config/pitest/<suite>-timeouts.csv` with a cause and argue it in the README; only
  `cause:liveness` certifies. A member whose coordinate has left the population is
  removed by hand after one fresh history-free run with valid committed provenance
  omits it; while provenance is invalid, repair or rebase it first.
- Tests are deterministic: fixed seeds, no sleeps, a clock with a non-zero origin,
  stubs that return distinguishable non-default values, and the subject built inside
  the test body. Exclusions must cover the test source set, not a naming convention.
- Verify by the absence of failures: trust the exit code and the `.running`
  sentinel, not a summary. `MINION_DIED` and `RUN_ERROR` are not results; re-run. A
  suite that got faster without getting narrower is a bug report.
- Fuzz findings become a committed seed input and a named regression test. Run
  `fuzzAll` locally with an explicit `-PmaxFuzzTime` and `-PmaxParallelFuzzTargets`
  before a release. Where one thing has two representations, fuzz the differential.
- `./gradlew :module:hardeningHelp` lists the installed tasks and options;
  sava-build's HARDENING.md holds the argument behind every rule above.
<!-- hardening-template block:end -->

For this repo, iterate with `:ix-proxy:test`; changes that can reach mutated
code, including test-only edits, owe `pitestIxProxy`, while doc, comment, and
build-script-only changes owe no mutation suite (a build-script change that
moves the mutation toolchain takes `pitestIxProxyBaselineRebase`, as the block
above says). `hardeningCertify` is owned by the local release checklist;
`:hardeningCertifyAll` certifies every suite and also writes the root manifest
`.pitest-history/pitest-certification-all.tsv`. This GLAM repo is outside the
Sava ArcMutate certificate and certifies with open-source PIT.

`IxMapperFuzz` builds its mapper in a static field by design (one mapper for
every input), and `IxMapperFuzzSeedsTests` replays the committed seeds through
that same static `MAPPER`; the other JUnit suites build theirs inside the test
body. If mapper-construction wiring mutants wander or survive, construct the
mapper inside the test body before diagnosing elsewhere.
