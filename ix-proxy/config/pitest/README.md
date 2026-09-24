# Mutation-testing triage record

The `ixProxy` accepted baseline holds the five unkilled rows of the document mapper (1089
mutants, 1084 killed on the recorded run, under PIT 1.25.9), each with a family label
whose equivalence argument is below; the timeout audit has no members. The installed
sava-build version's `hardeningHelp`, generated agent template, and `HARDENING.md` are
authoritative for task and record semantics. Use its named writer tasks for record
changes; never hand-edit record structure or provenance stamps.

## Triage history

- **Seeded 2026-07-29** and worked down through 2026-08-26 for the index-map mapper
  (`ProgramMapConfig`, `TransactionMapper`, `ConfigLoader` and the proxy classes): 13
  accepted rows, two audited liveness timeouts, no `NO_COVERAGE`. That code is gone.
- **Rewritten 2026-09-24** on the mapping document (`JsonSyntax`, `MappingDocumentParser`,
  `DocumentMapper`, `InstructionMapper` and the sealed model). Every mutated class is new,
  so the record was re-seeded whole with `pitestIxProxyBaselineUpdate` after the first
  survivors were argued, and the 13 old rows and both old timeout members left with their
  classes. The first history-free run of the rewrite reproduced 692 mutants with 518
  killed; the parser's refusal paths were then given a row per field, the redundant
  pre-checks that produced equivalent mutants were removed, and two local review rounds
  added the syntax pass, the deferred top-level checks, the binary64 number reads, the
  unreadable-instruction refusals and the format-preserving transaction rebuild, each with
  its rows. Later rows joined through `pitestIxProxyBaselineUnion` and were labelled; the
  one row those rounds killed left through `pitestIxProxyBaselinePrune` after two matching
  previews, and `pitestIxProxyBaselineRetag` refreshed the line tags.
- **Second review round, 2026-09-24:** a fuzz finding (a malformed UTF-8 byte the reader
  threw on) gave the syntax pass its UTF-8 and surrogate-escape checks, and the string
  scanner was restructured around one advance point (below). The records took their own
  field checks, with this library's wording so a disabled parser check stays visible to the
  contract's rows. The address bound's four `# cost-guard` rows left in two steps: a timing
  test killed the upper bound's row (pruned with the timing test in place) and was itself
  dropped for too thin a margin; then
  `MappingDocumentParserTests.theBoundAndTheAlphabetKeepStringsOutsideThemFromTheDecoder`
  (property: the decoder is never called for a string outside the spelling bound or the
  alphabet, checked with a counting decoder handed to the package-private `decodeAddress`
  overload) killed the other three, pruned once the counting test was in. The directory
  reader now keys a `TreeMap` by file name, so its comparator row left and the key's
  receiver row joined the same family through `pitestIxProxyBaselineUnion`. The recorded
  run: 2026-09-24, PIT 1.25.9, full scope, history-free (`-PnoMutationHistory`), the
  mappings root the tracked `ix-mapper-ts/` directory (copied from ix-mapper-ts 16320bf).

## Timed-out mutants (audited set)

None. The old `ConfigLoader$Worker.get` liveness members went with the class. During the
rewrite two `JsonSyntax` loop mutants (the `++i` of the array and string scanners turned
into `--i`) timed out once as unbounded loops, and in the second review a `MathMutator` on
the string scanner walked the cursor back onto the escape it had just read and hung. The
scanner now moves its cursor through `take()` alone, with no other assignment or
arithmetic on it: a reversed cursor fails on its first read, and every other mutant of the
scan is a refusal or an admission the syntax rows observe. A shadow check over the entries
timed out once with its outer loop's exit forced true and now fails fast, since the loop
reads its entry at the top of each step.

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
HARDENING.md); the baseline CSV carries the exact keys.

- `# top-level-predicate-return` (`DocumentBuilder.test`, the duplicate-field branch): the
  field predicate returns `true` after skipping a field named twice; returning `false`
  there ends the object read early, and the build refuses the duplicate before it looks at
  anything the early end left unread, so the refusal is the same. The predicate's other
  returns are killed by the member-order rows.
- `# unreachable-equality` (`MappingDocumentParser.validateShape`, `plainNumber`): the
  boundary of `source <= lastOmittableSource`, where equal sources are refused earlier as a
  position forwarded twice; and the sign test `e > 0` on a printed exponent, reached only
  when the exponent is at least 21 or at most -7. In both the equal case cannot arrive, so
  the two boundary forms are observationally identical.
- `# equivalent path-suffix` (`MappingDocuments.readDirectory`, 2 rows): the naked
  receivers of `path.getFileName()` in the `.json` filter and in the `TreeMap` key. A
  path's string form always ends with its file name's string form, and within one
  directory ordering by path is ordering by file name, so neither mutant changes which
  files are read or in what order.

Shrinking a baseline is always an improvement; growing one requires a reason here.
