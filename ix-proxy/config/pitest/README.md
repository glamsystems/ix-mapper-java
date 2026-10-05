# Mutation-testing triage record

The `ixProxy` accepted baseline records the document mapper's equivalent mutants;
the family arguments below apply only to the named members. The baseline CSV holds
the exact keys and sibling multiplicity; the verify and debt listings report totals
and triage state. The timeout audit has no members.

The installed sava-build version's `hardeningHelp`, generated agent template, and
`HARDENING.md` are authoritative for task and record semantics. Use its named writer
tasks for record changes; never hand-edit record structure or provenance stamps.
Keep these arguments current in place; pass reports belong outside this README.
This GLAM library uses open-source PIT, without the Sava ArcMutate certificate.

## Record provenance

The mapping-document rewrite replaced the index-map mapper on 2026-09-24. Its
baseline was re-seeded with `pitestIxProxyBaselineUpdate`; the retired classes'
accepted rows and `ConfigLoader$Worker.get` timeout members left with that rewrite.
Subsequent reviewed additions, removals and line metadata used
`pitestIxProxyBaselineUnion`, `pitestIxProxyBaselinePrune` after matching fresh
previews, and `pitestIxProxyBaselineRetag`, respectively.

The PIT 1.25.9 to 1.30.0 transition on 2026-09-24 (sava-build 21.6.1) used
`pitestIxProxyBaselineRebase` after a fresh full history-free observation reproduced
the accepted population; it changed the version and toolchain stamps, not the rows.
The supplied-account work on 2026-09-25 changed line metadata through
`pitestIxProxyBaselineRetag`, without changing accepted members.

The existing record's observation provenance is 2026-09-26, PIT 1.30.0, full scope,
`-PnoMutationHistory`, with `-PglamMappingsDir` pointing at the GLAM monorepo's
`packages/glam/ix-mapper-ts` at a9b9ee9fd. The synced `ix-mapper-ts/` tree also
produced the same mutant statuses, so the acceptance does not depend on unsynced
supplied-account cases. The earlier rewrite observation used the tracked tree copied
from ix-mapper-ts 16320bf. These identify the evidence behind the record; the
committed version and toolchain sidecars remain the provenance authority.

## Timed-out mutants (audited set)

None. There is no live timeout-cause argument to maintain. Retired scanner and
shadow-check loop incidents and their refactors remain in git history; they do not
insure future timeouts. Any new timeout requires its own audit under the installed
policy.

## Mutator-set trials

`STRONGER` plus `EXPERIMENTAL_NAKED_RECEIVER`. The receiver mutator was trialed 2026-07-29
on the old code (355 → 355, zero fires) and stayed off; re-trialed on the rewrite on
2026-09-24 (`-PtrialMutators=STRONGER,EXPERIMENTAL_NAKED_RECEIVER`) it fired, 948 → 1009
generated, on the reader's fluent `skip()` calls, the exception's message accessor and the
path and string helpers, so it is enabled. Its two surviving receivers are argued below;
the message-accessor cluster was refactored out (`MappingDocumentException.detail()`
replaces `getMessage().substring(2)`). `plainNumber` reads a number's digits through
`BigDecimal` (construction, `stripTrailingZeros`, `scale`) and the `BigInteger` its
`unscaledValue()` returns (only `toString`), with no arithmetic on either, and both
mutators, trialed 2026-09-24 by running the suite's own set plus each candidate
(`-PtrialMutators=STRONGER,EXPERIMENTAL_NAKED_RECEIVER,` plus `EXPERIMENTAL_BIG_DECIMAL`,
then `EXPERIMENTAL_BIG_INTEGER`), added no mutant: under PIT 1.25.9, on an earlier
revision of the rewrite (1086 mutants), 1086 → 1086 generated each (the trial summary
counts the suite's own mutators as fired; the generated count is the evidence). Both stay
off.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families in
HARDENING.md); the baseline CSV carries the exact keys. Re-read each argument when
its code, callers or members change; a label does not accept another similar mutant.

- `# top-level-predicate-return` — member: `MappingDocumentParser$DocumentBuilder.test`,
  the `BooleanFalseReturnValsMutator` on the duplicate-field branch. The property is
  that a document with a duplicate top-level field is refused naming its first duplicate
  before other document-field checks. The field predicate records that duplicate and
  skips its value; returning `false` instead of `true` ends the object read early, but
  `build()` refuses the recorded duplicate before consulting fields left unread.
  The independent oracle is the parser's duplicate-field refusal contract, pinned by
  the duplicate top-level and two-duplicate rows in `MappingDocumentParserTests`,
  rather than the predicate's return value. This equivalence expires if later reading
  has an observable effect or can throw before the deferred duplicate refusal, if
  validation order changes, or if iterator completion becomes part of the result.
- `# unreachable-equality` — members: the `ConditionalsBoundaryMutator` in
  `MappingDocumentParser.validateShape` on `source <= lastOmittableSource`, and in
  `plainNumber` on `e > 0`. The shape property is that omittable seats follow strictly
  increasing, unique source positions. Its independent oracle is the mapping
  contract's refusal of duplicate forwarding and out-of-order omittable seats, pinned
  by those refusal rows in `MappingDocumentParserTests`. Equal positions are already
  refused by the earlier forwarding-uniqueness pass, so changing `<=` to `<` cannot
  admit them. The number property is JavaScript's number-to-string exponent layout,
  pinned by the literal expectations in `plainNumberPrintsAsJavaScriptDoes` and the
  schema-version exponent refusal rows. Exponent form is entered only for `n > 21`
  or `n <= -6`; `e = n - 1` is therefore at least 21 or at most -7, never zero, so
  changing `> 0` to `>= 0` cannot change the sign spelling. Revisit the shape member
  if uniqueness checks move or stop applying to every forwarded source, and the
  number member if exponent thresholds or calculation change to make zero reachable.
- `# equivalent path-suffix` — members: the `NakedReceiverMutator` on
  `path.getFileName()` in `MappingDocuments.readDirectory`'s `.json` filter and
  `TreeMap` key (`lambda$readDirectory$1` and `lambda$readDirectory$2`). The property
  is to read regular `*.json` files directly under one directory in file-name order.
  Its independent oracle is `MappingDocuments.readDirectory`'s public contract,
  pinned by `MappingDocumentParserTests.readsDocumentsFromFiles`: other extensions
  and nested files are excluded and the deliberately first-named document comes first.
  For the paths supplied by `Files.list` in one directory, replacing the file name
  with the complete path preserves the suffix and ordering: each path has the same
  parent prefix and ends in its file name. Revisit if enumeration mixes parents,
  recurses, uses a path provider with different string or comparison semantics, or
  if the key becomes observable beyond ordering the returned documents.

Shrinking a baseline is always an improvement; growing one requires a reason here.
