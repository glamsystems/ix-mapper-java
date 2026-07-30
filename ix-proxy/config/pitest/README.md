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
- **Refreshed 2026-07-29** after migrating `IxMapConfig.Parser` to the
  json-iterator `FieldMatcher`/`readByteArray`/`readIntArray` APIs: 284 rows
  (104 `SURVIVED`, 180 `NO_COVERAGE`) of 311 generated — the refactor
  deleted the hand-rolled two-pass `mark()`/`reset()` array loops and their
  44 mutants outright; the 7 rows in `Parser.create` carried across as pure
  line shifts.
- **Worked 2026-07-29** from 27/311 (8%) to 289/311 (92%): direct
  parse-and-assert suites for every config parser, transaction/table
  plumbing tests for `ProgramProxyMap`, `createProxy` validation and proxy
  behaviour tests for `IxMapConfig`/`PayerIxProxy`/`IdentityIxProxy`/
  `IxProxyRecord`, lookup tests for both program-proxy shapes, and a
  scripted `StubHttpClient` driving `ConfigLoader`'s remote worker without a
  socket. Baseline 284 → 22 rows: 12 accepted with reasons (below), 10
  `# untriaged`.

### Remaining `# untriaged` debt — the Worker retry path

`ConfigLoader$Worker.get` lines 119–129 and 149–150 (10 rows, all
`NO_COVERAGE`): the IOException retry loop — backoff arithmetic,
`Thread.sleep`, the retry log call — and the InterruptedException handler.
The worker's happy path is covered by `ConfigLoaderTests` via
`StubHttpClient`; the retry path calls `Thread.sleep` with a computed
backoff, so covering it deterministically needs a clock/sleep seam (see
ravina's `NanoClock` pattern) or acceptance of a real-wait test, which the
determinism rules forbid. Take the seam route if this path ever needs
hardening; until then it is recorded debt.

## Timed-out mutants (audited set)

None. Seed `ixProxy-timeouts.csv` with `-PinitTimeoutAudit` if a timeout
ever appears, and write its structural cause here.

## Mutator-set trials

`STRONGER` is the default. `EXPERIMENTAL_NAKED_RECEIVER` was trialed
2026-07-29 (`-PtrialMutators=STRONGER,EXPERIMENTAL_NAKED_RECEIVER`):
355 generated without → 355 with, zero fires (re-trialed after the
FieldMatcher migration the same day: 311 → 311) — this code returns records,
arrays and fresh instructions, not fluent receivers, so it stays off.
Re-trial if builder-style code is introduced. No mutated class performs
`BigInteger`/`BigDecimal` arithmetic, so `EXPERIMENTAL_BIG_INTEGER` was not
trialed.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

- `# defensive-null-tables` (6 rows, `ProgramProxyMap` lines 60/89/127):
  both operand directions of `tables == null || tables.length == 0` on the
  `== null` operand. sava's `TransactionRecord` never returns a null
  `tableAccountMetas()` — it uses the `NO_TABLES` empty-array constant — so
  the null operand is defensive against foreign `Transaction`
  implementations. Killing it would need a hand-rolled fake `Transaction`
  returning null, a test that restates the implementation rather than a
  property. The `length == 0` operands at the same coordinates are killed.
- `# delegation-equivalent` (1 row, `ProgramProxyMap.mapTransactionWithTables`
  line 114): removing the `numNewTables == 1` fast path routes a single
  added table through the multi-table branch, and sava's transaction factory
  normalizes a single-entry meta array back to the single-table form — the
  resulting transaction is identical; only the internal meta wrapper's
  identity differs, which no property-level assertion should pin.
- `# single-variant-guard` (2 rows, `IxMapConfig.createProxy` line 58): the
  false direction of both operands of
  `proxyType != null && proxyType != ProxyType.PAYER`. `ProxyType` has a
  single constant, so "declared type is not PAYER" is unsatisfiable and the
  guard cannot fire — the mutants are equivalent until a second proxy type
  exists. **Re-triage when a `ProxyType` constant is added**; both true
  directions are killed by the payer tests.
- `# invariant-guard` (1 row, `BaseIxProxy.validateMapping` line 29): the
  false direction of `cpiDiscriminatorBytes.length != cpiDiscriminator.length()`
  — `cpiDiscriminatorBytes` is `cpiDiscriminator.data()` captured in the
  constructor, so the two lengths agree by construction of every
  `Discriminator` implementation; the guard exists to catch a broken foreign
  `Discriminator` and cannot fire in-harness.
- `# empty-copy-equivalent` (2 rows, `IxProxyRecord.mapInstructionUnchecked`
  line 80): `len > 0` guards a payload `System.arraycopy`; at `len == 0`
  (instruction data is exactly the discriminator) the copy is a zero-length
  no-op, so both the boundary flip and the forced-true direction are
  behaviourally identical. `len < 0` is unreachable — the proxy
  discriminator write into the undersized target array fails first.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
