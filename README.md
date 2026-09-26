# ix-mapper-java [![Gradle Check](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml/badge.svg)](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml)

Rewrites Solana instructions into their GLAM proxy-program equivalents. A vault's delegate
builds an instruction the way any client of the native program would, and the mapper turns
it into the GLAM instruction that performs it through the vault; the proxy program applies
GLAM's access checks around the native call.

The rules are data, not code: one **mapping document** per source program and environment,
generated in the GLAM monorepo from the native and the proxy IDLs. The TypeScript package
[`@glamsystems/ix-mapper`](https://github.com/glamsystems/ix-mapper-ts) and this library read
the same documents under the same rules; the conformance cases that ship with the documents
run against both, and the mapping vectors join them once a sync carries them here.

The library is the `ix-proxy` module: Maven coordinates `systems.glam:ix-proxy`, JPMS module
`systems.glam.ix_proxy`, package `systems.glam.ix.proxy`, Java 25. Its direct dependencies
are [sava-core](https://github.com/sava-software/sava) for keys, instructions and
transactions (it brings BouncyCastle) and json-iterator for the documents. Releases are on
GitHub Packages, so resolving it takes a token with `read:packages`; building from source
takes the same credentials for sava-build (see [AGENTS.md](AGENTS.md)). The jar carries no
documents: `glam-sdk-java` depends on this library and embeds the documents of both
environments, and a service that uses the library directly reads them from a directory or
parses them from bytes. The documents that go with a release are the ones under
`ix-mapper-ts/` at its tag: the parser refuses any field or label it does not know, so a
document that uses one this release has not learned is refused at load even at the same
`schema_version`, and one that uses only known fields loads.

## Usage

```java
// The documents of one environment, one file per source program: `production` is what
// GLAM runs on mainnet, `staging` the staging deployment with its own proxy program ids.
// The tracked ix-mapper-ts/src/generated/mapping/<environment> directory here, or a
// consumer's copy. A set that forms no mapper (none, two environments, two documents for
// one program) throws MappingDocumentException; so does a document that does not admit.
var documents = MappingDocuments.readDirectory(Path.of("ix-mapper-ts/src/generated/mapping/production"));
var mapper = InstructionMapper.createMapper(documents);   // immutable; share it freely

// What a mapping needs from the caller. The mapper derives no address: the vault's state
// and vault accounts, the key that signs the GLAM instruction (the owner or a delegate),
// the integration authority of a proxy program (for the documents that seat one) and the
// accounts a document lists as supplied (a handler's price oracles, a strategy's market)
// all come from the context. The three-argument constructor leaves out the lookup and
// the supplier, the four-argument one only the supplier; an instruction that needs
// either is then refused, never guessed.
var context = new MappingContext(glamState, glamVault, glamSigner,
    proxyProgram -> authorities.get(proxyProgram),
    request -> resolvers.supply(request));

// One instruction: mapping throws nothing of its own, every outcome is a result. (An
// Error from the caller's lookup or supplier propagates.)
switch (mapper.map(instruction, context)) {
  case MapResult.Mapped mapped -> send(mapped.instruction());
  case MapResult.Passthrough passthrough -> send(passthrough.instruction()); // GLAM does not proxy it
  case MapResult.Unsupported unsupported -> log(unsupported.reason(), unsupported.message());
}

// A whole transaction: maps every instruction first, then rebuilds the transaction with
// each mapped instruction in its place. The rebuild keeps the fee payer, the recent
// blockhash and the lookup tables (a v0 transaction that holds none comes back legacy),
// and for a v1 transaction its version and its settings; it is unsigned, and when nothing
// mapped the caller's own object comes back. Throws UnsupportedInstructionException at
// the first refusal, naming its position, and sava's own exception for a transaction the
// mapped instructions cannot form (a v1 transaction pushed past 64 accounts, say; each
// replacement is checked, so a form at or near a limit can be refused on the way). A
// legacy or v0 rebuild is not size-checked: a mapped instruction is longer than the
// native one, so check the result against the packet limit before sending. A v0
// transaction's lookup-table accounts must be loaded before mapping; an instruction that
// references an unresolved one comes back unreadable.
var mappedTransaction = mapper.mapTransaction(transaction, context);

// Whether GLAM has a document for a program, before building anything: the document, or null.
var document = mapper.documentOf(program);
```

A `MapResult.Mapped` carries the proxy instruction, the source program, the entry's name
and the handler's name. A `Passthrough` carries the caller's own `Instruction` object, the
program, the entry's name when a `passthrough` entry matched (else null) and the reason:
the entry's, or "the program has no mapping document". An `Unsupported` carries the
program, the entry's name when one matched, an
[`UnsupportedReason`](ix-proxy/src/main/java/systems/glam/ix/proxy/UnsupportedReason.java)
and a message that says what refused it:

| Reason | When |
| --- | --- |
| `unknown_instruction` | the program has a document, but no entry's discriminator prefixes the data |
| `refused_instruction` | the entry is `unsupported`; the message is the document's reason |
| `account_count` | the instruction leaves out an account the entry needs |
| `account_expectation` | an account is not the one the entry expects at its position (the vault, the GLAM signer, a pinned address) |
| `account_privilege` | a forwarded account's signer privilege disagrees with its seat (unless the document leaves the signer to the caller) |
| `remaining_accounts` | accounts beyond the listed positions, on an entry that forbids them |
| `context` | the context supplies no address for a GLAM account the entry seats or expects (no lookup, a null answer, or a lookup that threw), or no accounts for an entry that lists supplied ones (no supplier, a null answer, or a supplier that threw) |
| `supplied_accounts` | the supplier answered with the wrong number of accounts, or a null one |
| `unreadable_instruction` | the instruction itself cannot be read: a data span outside its buffer, or an account a transaction left unresolved |

An instruction of a program with no document passes through, unless it cannot be read:
GLAM does not proxy that program. The mapper never derives an address (an associated token
account, a PDA), never reorders or drops what the caller passed beyond what the entry
states, and relays the instruction data verbatim behind the swapped discriminator.

## Supplied accounts

An entry lists **supplied accounts** when its handler reads accounts from its remaining
accounts that a native instruction never carries. In the staging documents the monorepo
generates, Orca's liquidity handlers read the price oracles of the pool's mints and
Loopscale's `update_strategy` the strategy's market; no document synced here lists any
yet. The mapper asks the context's supplier once per such instruction, after the position
and seat checks and before the remaining-accounts check (so a supplier can be asked for an
instruction that is then refused), with a
[`SuppliedAccountsRequest`](ix-proxy/src/main/java/systems/glam/ix/proxy/SuppliedAccountsRequest.java):
the proxy and source programs, the entry and handler names, the roles in the order the
accounts are inserted, each with the addresses found at its `of` positions (the mints an
oracle is for), and the instruction itself. The supplier answers with the accounts in that
order, the required ones first, and may leave out a trailing run of optional ones; the
answer is copied once inside the same `try` as the call, so a list that throws while being
read counts as a supplier failure (`context`). The accounts are inserted after the seats
and before the accounts beyond the list, read-only and unsigned. The supplier interprets
the roles; the mapper does not.

## Mapping documents

A document names its environment, its source program and its proxy program, and lists the
source program's instructions with a **disposition** each:

- `map`: the proxy program has a handler; `handler` names it, `source_accounts` describes the
  native instruction's account list (flags, optionals, expectations), `destination_accounts`
  the handler's seats, each dynamic (a GLAM account the context supplies), static (a fixed
  address) or forwarded from a source position, `supplied_accounts` lists what the context
  supplies after the seats (a role, the source positions whose addresses the supplier
  receives with it, whether it may be left out), and `remaining_accounts` says whether
  accounts beyond the list may ride along;
- `passthrough`: the instruction is sent as it is, with the reason;
- `unsupported`: GLAM refuses it, with the reason.

The parser ([`MappingDocumentParser`](ix-proxy/src/main/java/systems/glam/ix/proxy/MappingDocumentParser.java))
admits only JSON text (RFC 8259 in well-formed UTF-8, surrogate escapes paired, nesting at
most 64 deep, nothing after the document) holding a well-formed document: an unknown field
or label, a field named twice, a `schema_version` other than 1, a discriminator that is a
prefix of another's, a seat that forwards a position read-only which the native
instruction declares writable, supplied accounts on an entry with a seat a client may
leave out or naming an optional position, and every other shape the rules forbid are
refused at load with a message that says where (the label, the program, the entry) and
what is wrong. Numbers are binary64 values, as JavaScript reads them (`1.0` is the integer
1); positions and seat indexes must be non-negative integers in `int` range, discriminator
bytes 0 to 255. A document built from the records (`MappingDocument`, `InstructionEntry`,
…) takes the field checks in each record's constructor and the checks across entries when
a mapper is created over it. The messages are the document contract's, shared with the
TypeScript mapper; where this parser refuses more than the contract, the message is its
own, and `MappingDocumentParserTests` pins those rows.

## Build & tests

```shell
./gradlew check
```

The tests read the generated documents, the conformance cases and, once a sync carries
them, the mapping vectors from the tracked [`ix-mapper-ts/`](ix-mapper-ts/README.md)
directory, in the layout of the GLAM monorepo's `packages/glam/ix-mapper-ts` package. The
monorepo's public-sync workflow writes it in a commit that names the monorepo commit it
came from, and writes the same documents into `glam-sdk-java` and the public
[ix-mapper-ts repository](https://github.com/glamsystems/ix-mapper-ts) in the same run; the
first copy was made by hand from that repository at 16320bf. Nothing under its `src/` and
`test/` is edited here: an upstream change is tested here when its sync commit lands. To
run the suite against a local checkout of the package before that:

```shell
./gradlew check -PglamMappingsDir=/absolute/path/to/ix-mapper-ts
```

The path is absolute or relative to the repository root; the mapping content is a declared
test input, so a changed tree re-runs the suite rather than serving a cached result.

Three kinds of test input come from that tree. The **conformance cases** under
`test/data/cases` are the contract: hand-written scenarios, each an instruction, a context
and the result the rules give for it, compared whole, message included, and every one runs
here. The **mapping vectors** under `test/data/vectors` are generated by the monorepo from
every entry of every bundled document: the TypeScript mapper's own output for a canonical
instruction, one vector per variant the entry admits. Replaying them holds this mapper to
that one on every entry; they prove agreement, not correctness, so the mutation suite
leaves `MapperVectorsTest` out. They arrive with the sync that carries the first documents
listing supplied accounts: before it the factory is skipped, after it a missing or empty
vectors directory fails. The **documents** themselves are read by `BundledDocumentsTest`: each file admits,
is named for its program and filed under its environment, and every sentinel seat
rewrites an absent optional to the proxy program, so a document the monorepo generates
and this parser refuses fails this repository's CI on its sync commit.

## Hardening

The `ix-proxy` module registers the PIT mutation suite `pitestIxProxy` and the Jazzer fuzz
targets `fuzzMappingConfig` (the document parser over arbitrary bytes) and `fuzzIxMapper`
(the mapper over instructions carved from arbitrary bytes, against two synthetic documents,
under a supplier the input selects) via sava-build's hardening feature; unkilled mutants
are ratcheted against the accepted baseline in [ix-proxy/config/pitest](ix-proxy/config/pitest),
and `IxMapperFuzzSeedsTests` and `MappingConfigFuzzSeedsTests` pin the outcome of every
seed under [ix-proxy/src/test/resources/fuzz](ix-proxy/src/test/resources/fuzz). See
[AGENTS.md](AGENTS.md) for the process contract.

## Releases

Conventional commits on `main` drive release-please; a tagged release publishes
`systems.glam:ix-proxy` to GitHub Packages (`publish-gh.yml`). A consumer takes a document
change and the library release that reads it together (see the note on `schema_version`
and unknown fields above).
