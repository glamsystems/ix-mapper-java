# Fuzz seed corpora

Each corpus is replayed inside `check` by its generated `*FuzzSeedReplayTest`, and also by
its seeds test (`IxMapperFuzzSeedsTests`, `MappingConfigFuzzSeedsTests`), which asserts the
outcome each seed's name promises. A finding becomes a named seed here **and** a regression
test.

## mappingConfig

Arbitrary bytes parsed as a mapping document, exactly as `MappingDocuments.read` parses a
file: `MappingDocumentParser.parse(bytes, label)`, then `InstructionMapper.createMapper`
over the result (see `MappingConfigFuzz`). Malformed-input contract: garbage in ->
`MappingDocumentException` out, whether the bytes are not JSON or the document does not
admit; any other throwable is a finding. Seeds:

- `system-production.json`, `kamino-production.json`, `marinade-staging.json`: real
  generated documents (the System program, Kamino Lending, Marinade), copied from the
  tracked `ix-mapper-ts/` directory.
- `cctp-production.json`: the generator's document for CCTP's token messenger, the mapping
  contract's example of a supplied account at an account index (`bridge_routes` at account
  index 7 of `deposit_for_burn`, with its derivation); it admits, and
  `MappingDocumentParserTests` and `InstructionMapperTests` read it too.
- `empty-object`, `no-instructions`: the empty document and one with no entries.
- `malformed-utf8-environment`: the 2026-09-24 finding, a document whose environment string
  holds a lead byte with no continuation (`66 C8 75`); the syntax pass refuses it as not JSON,
  and `MappingDocumentParserTests.aMalformedUtf8ByteInAStringIsNotJson` pins the refusal.
- `supplied-accounts`: a map entry listing supplied accounts (a required role with `of`, a
  required role without, an optional role with two positions) beside a passthrough entry;
  it admits. Each other `supplied-*` seed keeps one of those two entries, changed so that it
  breaks one rule, and is refused:
  - `supplied-optional-false`: the map entry, `market` with `"optional": false`;
  - `supplied-of-out-of-range`: the map entry, `price_oracle` with `of: [3]`, past the
    three positions;
  - `supplied-of-program-id`: the map entry, `price_oracle` with `of: [2]`, the
    `program_id` optional;
  - `supplied-of-omittable`: the map entry with a fourth, `omitted` position, and
    `price_oracle` with `of: [3]`, naming it;
  - `supplied-behind-omittable-seat`: the map entry with a fourth, `omitted` position and a
    sixth seat forwarding it;
  - `supplied-required-after-optional`: the map entry with its supplied accounts cut to
    `market` then `price_oracle`;
  - `supplied-on-passthrough`: the passthrough entry, carrying
    `"supplied_accounts": [{"role": "price_oracle"}]`.
- `supplied-at-account-index`: a map entry with two supplied accounts at an account index
  (`routes` at 2, derived from a constant, the state at account index 0, a forwarded account
  at 3, the sentinel's account index 5 and an argument; `ledger` at 4, writable, with no
  derivation) and an optional supplied account it lists, beside a passthrough entry; it
  admits. Each other `supplied-at-account-index-*` seed changes it to break one rule, and is
  refused:
  - `supplied-at-account-index-signs`: `routes` signs;
  - `supplied-at-account-index-from-a-supplied-account`: a seed of `routes` names account
    index 4, `ledger`;
  - `supplied-at-account-index-out-of-range`: a seed names account index 7, past the seven;
  - `supplied-at-account-index-from-an-omittable-position`: a fourth, `omitted` position
    forwarded at account index 7, which a seed names (and no supplied account listed, which
    an omittable position would refuse first);
  - `supplied-at-account-index-long-constant`: the constant seed is 33 bytes;
  - `supplied-at-account-index-unknown-seed-kind`: the constant seed's kind is `pda`.

## ixMapper

Arbitrary bytes carved into an instruction and mapped against a fixed two-document mapper
(see `IxMapperFuzz` for the carve layout: program and what the context's supplier does,
account count and pool rotation, flag bits, a sentinel switch, a data span that may point
outside the buffer). Document A's `full` entry covers every account kind but `supplied`,
both optional kinds, a sentinel and an expectation; its `priced` entry supplied accounts,
two required and an optional one; its `routed` entry two supplied accounts at an account
index (`routes` at 2, whose derivation names a GLAM account, a fixed address, a forwarded
account and the sentinel's account index 6, and `ledger` at 5, writable) and an optional
one it lists; document B's `strict` entry two caller-chosen signers (`dynamic_signer`),
one at a signing seat and one at an unsigned seat, and `remaining_accounts: none`. The
harness checks the mapped shape against the document (proxy program, data, seat by seat,
then the supplier's answer), and a refusal for the context or the supplied accounts against
what the supplier did, which restates the mapper's own rules: it catches crashes, escapes,
shape departures, a mapping that dropped a position the document does not let a client
omit, a forwarded account seated with a signer flag it does not hold and an outcome that
departs from the supplier's answer, not any other instruction the rules should have refused
but mapped; those expectations are the conformance cases (`MapperConformanceTest`) and the
seeds' pinned outcomes below (result kind, entry, the whole mapped account list with its
flags, or the refusal reason and message, or the passthrough reason, and the times the
supplier was asked).
Seeds, named for the outcome they reach:

- `a-full-every-position`, `a-full-omitted-trailing`, `a-full-sentinel-rewrite`,
  `a-full-remaining-accounts`: mapped, through each shape of document A's entry.
- `a-full-no-supplier`: mapped under a context with no supplier, which an entry that lists
  no supplied accounts never asks.
- `a-full-too-few`, `a-full-wrong-owner`, `a-full-signing-thing`: refused for the account
  count, the vault expectation and a signer at an unsigned seat.
- `a-priced-all`, `a-priced-required-only`: mapped, the supplier answering every supplied
  account (with two accounts beyond the list after them) or only the required ones.
- `a-priced-no-supplier`, `a-priced-null-answer`, `a-priced-supplier-throws`: refused for
  the context; the first two carry one message and differ in whether the supplier was
  asked.
- `a-priced-too-few`, `a-priced-too-many`, `a-priced-null-element`: refused for the
  supplied accounts.
- `a-routed-all`, `a-routed-required-only`: mapped, the answers for `routes` and `ledger` at
  their account indexes, then the optional `oracle` or nothing.
- `a-routed-sentinel-rewrite`: mapped with program A at position 2, so account index 6 holds
  the proxy program, which the supplier checks the derivation of `routes` names there.
- `a-routed-remaining-accounts`: mapped, the accounts beyond the list after `oracle`.
- `a-routed-no-supplier`, `a-routed-too-few`, `a-routed-null-element`: refused for the
  context and for the supplied accounts.
- `a-passthrough`, `a-refused`, `a-unknown-discriminator`, `a-empty-data`: the other
  dispositions and no match.
- `a-span-past-the-buffer`: a data span outside the buffer, refused as unreadable.
- `b-strict-exact`, `b-strict-extra-account`, `b-strict-short-discriminator`: mapped, refused
  for an account beyond the list, and no match on a short discriminator.
- `b-strict-signing-thing`, `b-strict-unsigned-payer`: the caller-chosen signers; a signing
  `thing` is seated signed at its unsigned seat, and an unsigned `payer` is refused at its
  signing seat.
- `unknown-program`: a program with no document passes through.
