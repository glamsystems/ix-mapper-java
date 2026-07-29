# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy — the three legal
outcomes for a new survivor, determinism requirements, targeting rules —
lives in sava-build's `HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Pure line drift (every new row a same-status shift of a
stale one, populations unchanged) passes on its own with a notice —
refresh at a convenient moment. Anything else fails with a per-row
classification (`shifted` vs `newly covered` vs unexplained) and a churn
tally: a newly covered row is triage, not churn, and identical rows are
sibling mutants of one compound condition — the comparison is a
multiset, so never hand-dedupe the CSV.

A baseline row may carry a trailing `# note` — `# untriaged` is the
conventional label for seeded debt. Notes are preserved across
`-PupdateMutationBaseline` / `-PunionMutationBaseline` rewrites, and the
verify task counts rows marked `# untriaged` so the debt stays a printed
number, not prose.

## Untriaged debt

A first baseline seeded from the pre-existing survivor population is triage
debt made explicit, not acceptance. List it here until each key is killed,
refactored away, or moved below with a reason.

- **Seeded 2026-07-29** (`pitestIxProxy -PupdateMutationBaseline`): 328 rows —
  148 `SURVIVED`, 180 `NO_COVERAGE` — against 27 killed of 355 generated.
  The `NO_COVERAGE` population is dominated by `ConfigLoader` (never
  exercised by a test) and the error/edge branches of the parsers
  (`ProgramMapConfig`, `IxMapConfig`, `DynamicAccountConfig`,
  `IndexedAccountMetaRecord`); the `SURVIVED` population by the mapping
  runtime (`BaseIxProxy`, `PayerIxProxy`, proxy lookup paths), where
  `IxMapperTest` executes the code but asserts too little of it. Use
  `pitestIxProxyDebt` to rank the remainder by class when picking the next
  cluster.

## Mutator-set trials

`STRONGER` is the default. `EXPERIMENTAL_NAKED_RECEIVER` was trialed
2026-07-29 (`-PtrialMutators=STRONGER,EXPERIMENTAL_NAKED_RECEIVER`):
355 generated without → 355 with, zero fires — this code returns records,
arrays and fresh instructions, not fluent receivers, so it stays off.
Re-trial if builder-style code is introduced. No mutated class performs
`BigInteger`/`BigDecimal` arithmetic, so `EXPERIMENTAL_BIG_INTEGER` was not
trialed.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
