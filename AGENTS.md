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

Full fuzz campaigns are deliberately local-only. There is no GitHub Actions
fuzz workflow because its CI runner cost is not justified for this library;
run `:ix-proxy:fuzzAll` locally with explicit `-PmaxFuzzTime` and
`-PmaxParallelFuzzTargets` budgets. CI still replays every committed seed
corpus inside `check`.

The full policy is sava-build's `HARDENING.md`; the process contract for
changes here:

<!-- The bounded block below is the exact agent-instructions template from
     sava-build's HARDENING.md, emitted by `hardeningAgentTemplate`;
     `agentsTemplateInSync` (wired into `check`) fails when the installed
     template changes until the block is re-diffed (`hardeningAgentTemplateDiff`)
     — ACT on each changed bullet (a new bullet may need code, not prose) — and
     the digest marker updated. Repository-specific facts live after the digest
     marker, never inside the block. -->
<!-- hardening-template block:start -->
- **Scale verification to the change.** Iterate with the module's `test`
  task; before handing off, run only the `pitest<Suite>`(s) whose mutated
  code the change can reach — including suites in dependent modules that
  call a changed API, and the owning suite for test-only edits (a weakened
  test is exactly what the ratchet catches). When the production-class inventory
  changes (add/remove/rename/move), or mutation target/exclusion rules change,
  also run the cheap whole-population
  `mutationOwnershipAudit` before handoff. The full `hardeningCertify` — every
  suite freshly observed, serialized, provenance-bound, diffed against
  `config/pitest/`, with strict timeout and ownership audits — is the pre-release
  check, owned by CI or by the release checklist (this repo records which); it is
  not the inner loop.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a
  test (prefer asserting the property it breaks over restating the
  implementation), **refactor** it out of existence, or **accept it** with a
  written reason in `config/pitest/README.md` **and a short family label on
  the row itself** — refreshes seed new rows `# untriaged`, and triage means
  replacing that label, so the baseline always says which rows are argued
  and which are debt. For an existing baseline, use `BaselineUnion` after
  reviewing the fresh rows: it appends them without deleting unmatched evidence.
  Reserve `BaselineUpdate` for a first seed or an independently reviewed complete
  rewrite; never run it just to make the build pass. A family label groups
  individually reviewed instances; it never authorizes the next syntactically
  similar mutant.
- **A mutant is a question, not a specification.** Before writing a killing
  test, state the externally intended property and an oracle independent of the
  current implementation: public contract, protocol specification, caller
  invariant, reference implementation, or domain rule. If it contradicts current
  behavior, first demonstrate the bug with a regression test that fails against
  the unmutated code, then fix production; never add a passing assertion that
  merely locks in the bug. At PR or handoff, report each nontrivial behavioral
  cluster — not each mutant — as `Property: ... | Oracle: ... | Outcome: missing
  assertion / production bug / accepted equivalent`. Test names and assertions
  normally carry the durable property; comment only when the oracle or unusual
  setup would otherwise be lost, and never embed PIT coordinates or line numbers.
- Baseline keys are line-less (`class,method,mutator,STATUS`) — editing
  above a mutated method churns nothing, and `# line` tags are review
  metadata. A new mutant replacing a killed one at the same key can inherit
  its acceptance, so treat a line-drift advisory whose written argument no
  longer fits the code as that swap until shown otherwise. After review, use
  `BaselineRetag` to refresh only matched line metadata while preserving every
  accepted row; never use an unrelated acceptance or deletion merely to clear
  the advisory. Use the installed plugin's named writer tasks and heed their
  candidate previews; never hand-edit
  record structure or provenance stamps. A PIT, PIT-plugin/tool-artifact,
  ArcMutate-base, or certificate change uses `pitest<Suite>BaselineRebase`: it
  preserves every old row, seeds new rows `# untriaged`, and stamps the reviewed
  toolchain only after a successful fresh observation. Perform a schema
  migration/rollback only with a fleet pin plan. A `[history]` report may check
  the ratchet but cannot support adding, removing, or relabelling
  accepted/timeout records; run `pitest<Suite> -PnoMutationHistory` first.
- Consumer hardening notes contain only local ownership, measurements, acceptance
  reasons, and provenance. `AGENTS.md` carries this exact generated,
  digest-pinned template with repository-specific facts outside its bounded block,
  but no independently maintained
  copy of plugin task semantics; use `hardeningHelp` and
  project-qualified `hardeningAgentTemplate` as the installed-version authorities,
  and run the matching read-only `hardeningAgentTemplateDiff` against its explicitly
  bounded block on every template-digest move before acknowledging the new marker.
- **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
  seconds instead of the full suite — then re-run unscoped with
  `-PnoMutationHistory` before any record decision; the tooling refuses to let
  a scoped report touch the baseline.
- Identical baseline rows are sibling mutants of one compound condition and
  the comparison is a multiset: never hand-dedupe. When one sibling
  survives, the verify names the killed sibling's test — the survivor is
  the opposite branch direction; triage it as its own mutant.
- **A survivor contradicted by an existing oracle may be contaminated evidence.**
  Open PIT's HTML **Covering tests** list, then compare the same scoped,
  history-free population with and without isolation:
  `-PmutateOnly=<class> -PnoMutationHistory`, then
  `-PmutateOnly=<class> -PisolateMutants`. An isolation-only kill points
  to state leaked between mutants — commonly a thread, executor, handler, or
  static fixture whose cleanup an earlier assertion failure skipped. Put
  teardown in `finally`/`try`-with-resources and rerun normally, history-free;
  isolated execution is diagnostic evidence, never a baseline decision.
- **Stubs and fixtures return distinguishable, non-default values.** A stub
  returning null/0/""/true/empty makes the matching return-value mutant
  equivalent by accident of the fixture — the clock non-zero-origin rule
  generalized to every stubbed return.
- **Copy-on-write clusters split by direction.** Assert immutability of
  returned collections (`assertThrows(UnsupportedOperationException, ...)`)
  at every size: the mutable-escape direction is a kill, not an acceptance;
  only the content-equal siblings are family-accepted equivalents.
- **Randomized tests use fixed seeds, and never sleep**: the ratchet needs
  deterministic kills, and PIT re-runs the suite per mutant, so one real wait
  costs minutes. Exploration belongs to the fuzz targets.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
  detected and is not written to the baseline, but it proves only watchdog
  detection. Load can change the observed status and line-less keys can conflate
  siblings. Verify a baseline in both modes; for measured load-flip insurance,
  union only rows observed to flip, never every `TIMED_OUT` row. This does not
  restrict additive `BaselineUnion` acceptance of separately reviewed fresh debt.
- **A new timed-out mutant is a reviewer-stop, not detection noise.** A timeout
  can mask a weakened assertion; audit a set, not a count. **Record.**
  `config/pitest/<suite>-timeouts.csv` holds line-less
  `class,method,mutator` keys and a cause; `# line` is diagnostic, while
  `config/pitest/README.md` records the full cause. Verification warns on outside
  timeouts and stale members. `pitest<Suite>Debt` previews the pre-PIT
  file check. `TimeoutAuditInit` seeds an uncertifiable file: classify every row.
  **Classify.** Only `cause:liveness` certifies: after deterministic seams and
  budgets, the mutated path has no path-owned finite completion. A fixture's
  emergency exit does not demote that loss; record its bound. A bound claimed
  as the deterministic oracle must beat PIT's
  `duration × timeoutFactor + timeoutConst`; otherwise shorten it and re-observe
  history-free — it contributes no cause evidence. A later emergency
  ceiling cannot prove liveness.
  A straight-line path without a loop, retry, lock, wait, blocking call, or external
  completion dependency is not credible liveness evidence. Prove the mutated path
  receives the test clock/budget and check for a synchronous state reader; a
  collaborator's `TestClock` cannot observe a system clock.
  Missing/unknown causes, `cause:untriaged`, finite `cause:resource`, and
  `cause:harness` are reviewer-stops; harness records a finite covering-path/watchdog
  race without authorizing it. Resource behavior needs its promised contract test/fix
  or a stable `SURVIVED` equivalence argument. Liveness authorizes `TIMED_OUT`, never
  `MEMORY_ERROR`: for a non-advancing loop racing the heap, make every covering path
  fail deterministically without relying on PIT test order, or refactor out the
  mutation site.
  **Disambiguate.** A cause covers every `TIMED_OUT` sibling under its key. A finite
  sibling observed `KILLED` or another valid non-timeout does not itself create
  mixed timeout causes, but a key
  cannot certify when trustworthy fresh evidence shows distinct same-key siblings
  timing out under different cause categories. One later `KILLED` does not erase that
  conflict; `KILLED`↔`TIMED_OUT` movement alone does not prove it. Repair the finite
  path and establish repeated fresh history-free non-timeout observations under
  solo/gate load, or split/refactor/eliminate the site. Multiplicity drift prints
  all current line-full candidates, but lines cannot define identity: moving imports,
  adding a method, or reflowing code never warns, fails, or requires re-anchoring.
  **Retire.** Remove an admissible liveness member only after the tool reports 3+
  distinct fresh full-run quiet observations over identical execution inputs,
  confirmed under solo/gate load. When retirement semantics are unchanged, a plugin
  fingerprint change alone does not reset this advisory; captured PIT-input changes
  do, and unmodeled semantic changes require a timeout-quiet format bump. A
  finite `KILLED`↔`TIMED_OUT` race never certifies: repair it instead of waiting on
  liveness retirement. The quiet stash is a machine-local nomination; never copy or
  merge it, and retain the row without same-input gate confirmation. Assisted
  reports are previews and advance neither timeout status nor quiet-run evidence.
- **A flaky harness is worse than recorded debt.** If an interleaving or a
  boundary cannot be made deterministic, accept the mutant with a written
  reason rather than chasing it with sleeps or spin-waits.
- **A suite's percentage is not a target.** An accepted mutant with a written
  reason is finished work, not debt. Before trying to raise a number, check
  whether the remainder is `NO_COVERAGE` (real work) or documented
  equivalents (already closed).
- **Allocation and timing harnesses are a last resort for thin constant-factor
  differences**, reserved for properties that are a stated design goal. A
  removed growth/capacity/amortisation guard that changes complexity class is
  not “allocation-size only”: use a small input with an orders-of-magnitude
  margin and the correct path through the mutated code. Harnesses re-run once
  per mutant, need a `volatile` sink so escape analysis cannot delete what they
  measure, and flap when the margin is thin.
- When a test you believe in will not go green, **suspect the code before you
  soften the assertion** — that is where this process finds real bugs.
- **A wandering unkilled count is a defect, not noise** — chase it before
  changing any baseline. Reproduce it under the relevant solo/gate loads,
  inspect per-mutant coordinates, remove real waits, and move construction
  coverage into the test body before deciding whether it is a product defect,
  a load-dependent timeout, or a harness defect.
- **Build the subject under test inside the test body, not in a field.**
  Under `PER_CLASS` lifecycle a field-initialized client's construction
  coverage attaches to whichever test runs first, so wiring mutants can
  never pair with the test that drives what they wire — they survive even
  under a harness that asserts every request. One test that constructs the
  client in the test method and drives each configured URL restores the
  pairing.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
  arithmetic and receiver-returning fluent calls can be invisible to the
  enabled defaults. Follow the plugin's trial advice per suite, enable only
  mutators proved to fire, and record the measured numbers and declines.
- Module-path and mutation-test service discovery can differ. Declare real
  services in every runtime representation the project supports, probe the
  active environment in test-only scaffolding, and never commit a harness
  whose pass/fail result depends on which task launched it.
- `SURVIVED` and `NO_COVERAGE` are different problems: the first is a
  judgment call about equivalence, the second is usually an untested line
  and is mechanical. Never accept a `NO_COVERAGE` mutant as "equivalent" —
  you have not observed its behaviour. One structural exception: a block
  that always exits by throw reads `NO_COVERAGE` forever, executed or not
  (PIT probes a block at its end), and its return-value mutants can never
  change status. Such a line is owed a test asserting the throw's contract,
  not coverage — and never leave one untested fearing a covered-line
  `SURVIVED` conversion, which would require the block to complete.
- Exclusions must cover the **test source set**, not a naming convention:
  shared fakes are named `RecordingFoo` / `StubFoo` and match no `*Test*`
  pattern. After registering or widening a suite, list the mutated classes and
  confirm none live under `src/test`.
- **Verify by the absence of failures, not the presence of passes.** Counting
  `PASSED` lines hides a failure sitting next to them, and a green
  `clean build` can mean the build cache short-circuited rather than that
  tests ran. Check the failure count and confirm the task actually executed.
  A mutation run has a second version of this: PIT writes reports incrementally,
  so a failed run can otherwise look complete. The plugin clears known
  decision-grade leaves before each attempt, writes `.running` until clean
  completion, and retains unfiltered `pitest.stdout.log` / `pitest.stderr.log`
  beside the selected report. Trust the exit code and sentinel, not a summary
  from a failed attempt. Use `pitest<Suite>Diagnostic` for isolated
  `VERBOSE_NO_SPINNER`, history-free investigation; its report and raw logs are
  machine-local diagnostic output, may contain sensitive test/process details,
  and can never support a record or certification decision.
- **A suite that got faster without getting narrower is a bug report.** Real
  speedups come from fewer mutants or faster covering tests; an unexplained
  one usually means the run did less than you think. Read the task's evidence
  markers and scope; only a fresh full certification may support a release.
  The process itself needs no ArcMutate licence and applies to any Java package.
- **Invalid execution outcomes are not results.** PIT `MINION_DIED` fails
  before writing a report, so it cannot corrupt one — re-run the suite; a
  Gradle-worker `EOFException` death is the same shape, and a per-mutant
  `RUN_ERROR` often first observed in a multi-suite run is the same
  shape smaller (load average itself proves nothing; the hardening parser refuses
  the report rather than certifying PIT's detected score). The refusal and
  `pitest<Suite>Debt` name every offending row; retain the coordinate before a
  quiet re-run replaces the report. `RUN_ERROR` alone diagnoses neither load nor
  memory and never justifies changing threads or heap; record load/RSS as context,
  retry once quietly, and tune only when PIT explicitly diagnoses a process-resource
  failure. Recurrence localizes a repeatable observation, not its cause: stable
  mutation-unit partition can report an aggregate-contention minion death at the same
  coordinate repeatedly. Compare fresh history-free full attempts with
  `-PmutateOnly=<class> -PnoMutationHistory`; a reliable scoped kill points away from
  the mutant alone without proving load, while a scoped batched/`-PisolateMutants`
  difference says the mutation-unit boundary matters — inspect leaked state first,
  then packing/process overhead. Run `pitest<Suite>Diagnostic` full and scoped when
  per-process progress is missing; its separate raw streams establish no total order,
  and the last announced mutation is context, not cause. Only a clean fresh full
  unscoped run can support records or certification.
  The daemon log
  (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
  full output even when the shell discarded it — read it before calling a
  failure unexplained.
- Fuzz findings become a committed seed input **and** a named regression
  test, never just a fix — and the committed corpus is replayed by a unit
  test inside `check`, so it cannot rot between fuzz runs.
- **Run fuzz campaigns explicitly and locally.** `fuzzAll` is derived from every
  registered target, so it cannot drift from a hand-written workflow task list;
  set and record `-PmaxFuzzTime=<seconds>` and
  `-PmaxParallelFuzzTargets=<count>` before release. Scheduled GitHub fuzz
  workflows are optional and are not release evidence.
- **When one thing has two representations, fuzz the differential.** Two
  parsers for one config, an encode/decode round trip, a fast path beside a
  reference path: assert the two *agree* rather than that neither crashes.
  Crash-only fuzzing cannot see a wrong answer.
- **Time-dependent code takes a clock**, so tests advance time instead of
  waiting. Give test clocks a non-zero origin — a clock starting at 0 makes
  every "start timestamp mutated to 0" mutant equivalent by accident.
<!-- hardening-template block:end -->
<!-- hardening-template sha256:f866084114e0 -->

For this repo, iterate with `:ix-proxy:test`; changes that can reach mutated
code, including test-only edits, owe `pitestIxProxy`, while doc, comment, and
build-script-only changes owe no mutation suite. `hardeningCertify` is owned by
the local release checklist. This GLAM repo is outside the Sava ArcMutate
certificate and certifies with open-source PIT.

`IxMapperTest` constructs its mappers in static fields. If mapper-construction
wiring mutants wander or survive, construct the mapper inside the test body
before diagnosing elsewhere.
