# Fuzz seed corpora

Each corpus is replayed inside `check` by its generated `*FuzzSeedReplayTest`, and the
mapper corpus also by `IxMapperFuzzSeedsTests`, which asserts the outcome each seed's name
promises. A finding becomes a named seed here **and** a regression test.

## mappingConfig

Arbitrary bytes parsed as a mapping document, exactly as `MappingDocuments.read` parses a
file: `MappingDocumentParser.parse(bytes, label)`, then `InstructionMapper.createMapper`
over the result (see `MappingConfigFuzz`). Malformed-input contract: garbage in ->
`MappingDocumentException` out, whether the bytes are not JSON or the document does not
admit; any other throwable is a finding. Seeds:

- `system-production.json`, `kamino-production.json`, `marinade-staging.json`: real
  generated documents (the System program, Kamino Lending, Marinade), copied from the
  tracked `ix-mapper-ts/` directory.
- `empty-object`, `no-instructions`: the empty document and one with no entries.
- `malformed-utf8-environment`: the 2026-09-24 finding, a document whose environment string
  holds a lead byte with no continuation (`66 C8 75`); the syntax pass refuses it as not JSON,
  and `MappingDocumentParserTests.aMalformedUtf8ByteInAStringIsNotJson` pins the refusal.

## ixMapper

Arbitrary bytes carved into an instruction and mapped against a fixed two-document mapper
(see `IxMapperFuzz` for the carve layout: program, account count and pool rotation, flag
bits, a sentinel switch, a data span that may point outside the buffer). Document A's
`full` entry covers every seat kind, both optional kinds, a sentinel and an expectation;
document B's `strict` entry a forwarded signer and `remaining_accounts: none`. The harness
checks the mapped shape against the document (proxy program, data, seat by seat), which
restates the mapper's own rules: it catches crashes, escapes, shape departures and a
mapping that dropped a position the document does not let a client omit, not any other
instruction the rules should have refused but mapped; those expectations are the
conformance cases (`MapperConformanceTest`) and the seeds' pinned outcomes below. Seeds,
named for the outcome they reach:

- `a-full-every-position`, `a-full-omitted-trailing`, `a-full-sentinel-rewrite`,
  `a-full-remaining-accounts`: mapped, through each shape of document A's entry.
- `a-full-too-few`, `a-full-wrong-owner`, `a-full-signing-thing`: refused for the account
  count, the vault expectation and a signer at an unsigned seat.
- `a-passthrough`, `a-refused`, `a-unknown-discriminator`, `a-empty-data`: the other
  dispositions and no match.
- `a-span-past-the-buffer`: a data span outside the buffer, refused as unreadable.
- `b-strict-exact`, `b-strict-extra-account`, `b-strict-short-discriminator`: mapped, refused
  for an account beyond the list, and no match on a short discriminator.
- `unknown-program`: a program with no document passes through.
