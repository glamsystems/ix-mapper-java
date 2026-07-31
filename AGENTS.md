# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

Everything here is portable — true of any checkout. Machine-specific context
(local sibling checkouts, credentials, observed timings) belongs in an
untracked `AGENTS.local.md`, not here.

## What this repository is

The GLAM instruction mapper: a small, dependency-light Java library that
rewrites arbitrary Solana instructions into their GLAM proxy-program
equivalents. Mapping rules are data, not code — per-program JSON configs
maintained in `glamsystems/ix-mapper-ts` — and this library parses them
(`ProgramMapConfig`), builds proxy lookups (`TransactionMapper`,
`ProgramProxy`, `IxProxy`), and remaps discriminators and account lists at
runtime. `glam-sdk-java` is the primary consumer.

### Layout

- `ix-proxy/` (JPMS module `systems.glam.ix.proxy`) — the whole library.
  Config parsing (`ProgramMapConfig`, `IxMapConfig`, `DynamicAccountConfig`,
  `ConfigLoader`), the proxy/mapping runtime (`TransactionMapper`,
  `ProgramProxy` impls, `IxProxy` impls, `IndexedAccountMeta`,
  `DynamicAccount`).
- `glam/` (untracked) — mapping configs cloned from
  `glamsystems/ix-mapper-ts` by `./downloadMappings.sh`. The build downloads
  it automatically when missing (tests parse and map through every config in
  it); `./syncMappings.sh` pulls updates. Never make anything depend on its
  contents being stable — CI fetches it fresh every run, deliberately, so a
  mapping-config regression upstream fails this repo's build.

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
  `CHANGELOG.md`. Tagged releases publish to Maven Central and GitHub
  Packages via sava-build's reusable publish workflow.

## This is a published library

Consumers (notably `glam-sdk-java`) depend on current behaviour. Pin a
surprising behaviour with a test and report it before changing it; additive
fixes are fine, changing what an existing signature returns is not, without
the user's say-so.

## Testing conventions

- JUnit 5, built-in `Assertions`, package-private `final class` tests in the
  **same package** as the code under test (JPMS whitebox patching is wired by
  the build plugin) — reach for package-private access, not reflection.
- `IxMapperTest` is a port of the TypeScript companion suite
  (`ix-mapper-ts/tests/index.spec.ts`) and drives real instructions through
  the downloaded production and staging configs. Tests never hit the network
  themselves — the one network step is the build's mapping download, which
  runs before tests and only when `glam/` is missing.
- Randomized tests use fixed seeds; nothing sleeps. Time-dependent code takes
  a clock seam, never the wall clock — give test clocks a non-zero origin.

## Hardening: mutation testing (PIT) and fuzzing (Jazzer)

The `ix-proxy` module registers the PIT suite `pitestIxProxy` via the
`software.sava.build.feature.hardening` plugin, targeting
`systems.glam.ix.proxy.*` by wildcard with test sources excluded, so a new
class is mutated by default. The run diffs unkilled mutants against the
accepted baseline in `ix-proxy/config/pitest/` and fails on anything new. The
baseline was seeded with the full pre-existing survivor population and has
since been **worked down to fully-triaged equivalents** — every row carries a
family label whose equivalence argument lives in `config/pitest/README.md`,
which also tracks the debt history and the audited timeout set.
`EXPERIMENTAL_NAKED_RECEIVER` was trialed and
generated zero additional mutants (twice — latest 311 → 311), so the suite
stays on plain `STRONGER` — re-trial if fluent/builder-style code is
introduced.

Two fuzz targets, with seed corpora under `ix-proxy/src/test/resources/fuzz/`
replayed inside `check` by generated `*FuzzSeedReplayTest`s:
`fuzzMappingConfig` (`MappingConfigFuzz`) drives the full startup path for
external, downloaded config JSON — `ProgramMapConfig.parseConfig` through
`createProgramProxies`; `fuzzIxMapper` (`IxMapperFuzz`) carves arbitrary
bytes into instructions and drives `lookupProxy`/`mapInstruction` — the path
that faces user-submitted transactions — asserting length/payload/program
properties on every successful mapping. Register new harnesses in
the `hardening` block with `targetClass` AND `seedCorpus` (both required — a
missing `seedCorpus` silently skips the replay test).

The full policy is sava-build's `HARDENING.md`; the process contract for
changes here:

<!-- This section adapts the agent-instructions template in sava-build's
     HARDENING.md; `agentsTemplateInSync` (wired into `check`) fails when the
     template changes until the block is re-diffed — sync or ACT on each
     changed bullet (a new bullet may need code, not prose) — and the digest
     updated. -->
<!-- hardening-template sha256:f6dea3f41ab7 -->

1. **Scale verification to the change.** Iterate with `:ix-proxy:test`;
   before handing off, run `pitestIxProxy` when the change can reach mutated
   code — test-only edits included (a weakened test is exactly what the
   ratchet catches). Doc, comment and build-script changes owe no suite.
   `qualityGate` (every suite, serialized) is the pre-release check, not the
   inner loop; it is owned by the **local release checklist** — CI
   deliberately runs only `check`, so run the gate locally before deciding
   to release.
2. **A new unkilled mutant has exactly three legal outcomes**: kill it with a
   test that asserts the property it breaks (not one restating the
   implementation), refactor it out of existence, or accept it with a written
   reason in `config/pitest/README.md` **and a short family label on the row
   itself** — refreshes seed new rows `# untriaged`, and triage means
   replacing that label, so the baseline always says which rows are argued
   and which are debt. Never run `-PupdateMutationBaseline` just to make the
   build pass.
3. **`SURVIVED` and `NO_COVERAGE` are different problems.** A survivor ran
   the line and the test could not tell — a judgment call about equivalence.
   A no-coverage mutant was never executed — mechanical work, and **never
   acceptable as "equivalent"**, because you have not observed its behaviour.
4. **Pure line drift passes on its own** — when every new baseline entry is a
   same-status shift of a stale one and the per-method population is
   unchanged, the verify passes with a notice; refresh at a convenient
   moment. Anything mixed in (newly covered, unexplained, changed counts) is
   triage first, refresh after. `-PnoDriftTolerance` restores strict mode for
   certifying runs. When stale rows are all since-killed and nothing is new,
   `-PpruneMutationBaseline` is the safe shrink-only refresh. The three
   refresh flags are mutually exclusive; the verify's stale-entry hint names
   the safe one per case — prefer it over any hand-rolled cleanup.
5. **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
   seconds instead of the full suite — then re-run unscoped before any
   refresh; the tooling refuses to let a scoped report touch the baseline.
6. **Identical baseline rows are sibling mutants** of one compound condition
   and the comparison is a multiset: never hand-dedupe the CSV. When one
   sibling survives, the verify names the killed sibling's test — the
   survivor is the opposite branch direction; triage it as its own mutant.
   Status is part of the row: a `NO_COVERAGE -> SURVIVED` flip is two
   different rows at one coordinate — another reason scripts must never
   touch the CSV.
7. **Determinism is the whole point.** Fixed seeds, no real waits (PIT
   re-runs covering tests once per mutant, so one sleep is multiplied by the
   mutant count), and no reliance on PIT's timeout: `TIMED_OUT` counts as
   detected but is load-dependent — the same mutant can report `SURVIVED`
   alone and `TIMED_OUT` under `qualityGate`. Verify baselines in both modes;
   union only rows observed to flip. A flaky harness is worse than recorded
   debt — if an interleaving cannot be made deterministic, accept the mutant
   with a written reason.
8. **A new timed-out mutant is a reviewer-stop, not detection noise.** Each
   suite's timeouts are an audited set (`config/pitest/<suite>-timeouts.csv`,
   line-less `class,method,mutator` keys) with the structural cause per
   member in the README; the verify warns on any timeout outside the set and
   on members matching no mutant. This suite currently has none — seed the
   audited set with `-PinitTimeoutAudit` if one ever appears, and write its
   cause.
9. **A suite's percentage is not a target.** An accepted mutant with a
   written reason is finished work, not debt. Before trying to raise a
   number, check whether the remainder is `NO_COVERAGE` (real work) or
   documented equivalents (already closed).
10. **Stubs and fixtures return distinguishable, non-default values.** A stub
    returning null/0/""/true/empty makes the matching return-value mutant
    equivalent by accident of the fixture.
11. **Allocation and timing harnesses are a last resort**, reserved for
    properties that are a stated design goal; they need a `volatile` sink and
    flap when margins are thin.
12. When a test you believe in will not go green, **suspect the code before
    you soften the assertion** — that is where this process finds real bugs.
13. **A wandering unkilled count is a defect, not noise** — chase it before
    refreshing any baseline. Known causes: real waits, `TIMED_OUT` load
    flips, `@Execution`/`@TestInstance` on an abstract base not reaching
    concrete classes, and coverage attributed to field initializers —
    exercise factories from inside a `@Test`. Note: `IxMapperTest` builds its
    mappers in static fields; if wiring mutants in the mapper-construction
    path wander or survive unexpectedly, build the mapper inside the test
    body (the template's "build the subject in the test body" rule) before
    suspecting anything else.
14. **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
    arithmetic needs `EXPERIMENTAL_BIG_INTEGER`; fluent receiver-returning
    calls need `EXPERIMENTAL_NAKED_RECEIVER`. Trial per suite
    (`-PtrialMutators=...`), enable only what fires, and record the numbers
    in `config/pitest/README.md`.
15. **PIT minions run on the class path**, even in module-path repos:
    `module-info` services are invisible to them. Real services are declared
    in both `module-info` and `META-INF/services`; a harness whose result
    depends on which task ran it is never committed.
16. Exclusions must cover the **test source set**, not a naming convention:
    shared fakes are named `Recording*` / `Stub*` and match no `*Test*`
    pattern. After registering or widening a suite, list the mutated classes
    and confirm none live under `src/test`.
17. **Verify by the absence of failures, not the presence of passes.** A
    failed PIT run leaves the previous run's report in place; trust the exit
    code, and delete report directories when comparing runs. A suite that got
    faster without getting narrower is a bug report. Transient infra failures
    (`MINION_DIED`, worker `EOFException`, per-mutant `RUN_ERROR` under load)
    are not results — re-run; the Gradle daemon log
    (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed
    build's full output.
18. **Fuzz findings become a committed seed input AND a named regression
    test**, never just a fix — the committed corpus is replayed inside
    `check`, so it cannot rot between fuzz runs. When one thing has two
    representations, fuzz the differential — crash-only fuzzing cannot see a
    wrong answer.
