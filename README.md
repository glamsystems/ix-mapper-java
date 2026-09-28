# ix-mapper-java [![Gradle Check](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml/badge.svg)](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml)

Rewrites a Solana instruction built for a program into the instruction a **proxy program**
takes to perform it on the caller's behalf. A proxy program stands between a client and a
target program: it seats some accounts of its own, applies its checks, and forwards the
call to the target through a CPI. A client that already knows how to build the target's
instruction should not have to learn each proxy's account layout; the mapper turns the one
into the other, and the rules it follows are data, not code, apart from a fixed set of
four dynamic account names (see *Using it for another proxy*).

That data is a **mapping document**: one per target program and environment, stating for
each of the target's instructions whether the proxy takes it (and how), lets it through, or
refuses it. The document format, the rules for reading it and the mapper are general. The
documents this repository tracks and tests against are GLAM's: its proxy programs let a
vault's owner or delegate use Kamino, CCTP and the token programs through the vault in
production, and Orca, Loopscale and others in its staging deployment, and the documents are
generated in the GLAM monorepo from the target and proxy IDLs. The TypeScript package
[`@glamsystems/ix-mapper`](https://github.com/glamsystems/ix-mapper-ts) reads the same
documents under the same rules; the conformance cases that ship with the documents run
against both mappers, and the mapping vectors hold the two to the same output on a
canonical instruction for every entry.

The library is the `ix-proxy` module: Maven coordinates `systems.glam:ix-proxy`, JPMS module
`systems.glam.ix_proxy`, package `systems.glam.ix.proxy`, Java 25. Its direct dependencies
are [sava-core](https://github.com/sava-software/sava) for keys, instructions and
transactions (it brings BouncyCastle) and json-iterator for the documents. Releases are on
GitHub Packages, so resolving it takes a token with `read:packages`; building from source
takes the same credentials for sava-build (see [AGENTS.md](AGENTS.md)). The jar carries no
documents: a consumer reads them from a directory or parses them from bytes (`glam-sdk-java`
depends on this library and embeds GLAM's documents of both environments). The documents
that go with a release are the ones under `ix-mapper-ts/` at its tag: the parser refuses any
field or label it does not know, so a document that uses one this release has not learned
is refused at load even at the same `schema_version`, and one that uses only known fields
loads.

## The model

A mapped instruction is built from three kinds of account, in this order:

1. **Seats**: the proxy instruction's own account list, as its IDL declares it. Each seat is
   **dynamic** (an account of the proxy's world that the caller supplies at mapping time:
   for GLAM, the vault's state, the vault, the key that signs, and a proxy program's
   integration authority), **static** (a fixed address: a program, a sysvar, a
   configuration PDA), or **forwarded** from a position of the target instruction (`"kind":
   "source"` in the document), with the flags the proxy declares for the seat, except that
   an account at a position whose IDL leaves the signer to the caller (`dynamic_signer`)
   keeps the caller's signer flag at an unsigned seat and must sign at a signing seat. A
   forwarded seat may be a **sentinel**: when the target position is an optional the client
   passed as the target program's id (Anchor's spelling of an absent account), the seat gets
   the proxy program's id instead, read-only and unsigned.
2. **Supplied accounts**: accounts the proxy reads from its remaining accounts that the
   target instruction never carries, named by a **role** the caller's context resolves at
   mapping time (for GLAM, a pool's price oracles, a strategy's market).
3. **Remaining accounts**: everything the caller passed beyond the target's listed
   positions, forwarded after the supplied accounts with the flags the caller gave, when the
   entry allows them.

The target instruction's positions carry their own rules. An **expectation** pins what a
position must hold; the mapper checks it whether the position is forwarded or dropped. GLAM's
documents set one on every position the proxy drops and stands in for: the vault at an
owner, an authority or, for some handlers, a payer; the signer at most payers and at a token
close's destination; or a pinned program or sysvar address. A position that is neither
forwarded nor expected is dropped unchecked. An **optional** position says how an absent
account reaches the target, left out of the list or passed as the program id. The mapper
never derives an address (an associated token account, a PDA), never reorders or drops what
the caller passed beyond what the entry states, and relays the instruction data verbatim
behind the swapped discriminator.

## Usage

```java
// The documents of one environment, one file per target program: the tracked
// ix-mapper-ts/src/generated/mapping/<environment> directory here (GLAM's `production`
// and `staging` deployments, both on mainnet, each with its own proxy program ids), or a
// consumer's own set. A set that forms no mapper (none, two environments, two documents
// for one program) throws MappingDocumentException; so does a document that does not
// admit.
var documents = MappingDocuments.readDirectory(Path.of("ix-mapper-ts/src/generated/mapping/production"));
var mapper = InstructionMapper.createMapper(documents);   // immutable; share it freely

// What a mapping needs from the caller. The mapper derives no address: the dynamic
// accounts (for GLAM, the vault's state and vault, the key that signs the proxy
// instruction, an owner or a delegate, and, through a lookup by proxy program, the
// integration authority for the documents that seat one) and the supplied accounts (a
// handler's price oracles, a strategy's market) all come from the context. The
// three-argument constructor leaves out the lookup and the supplier, the four-argument one
// only the supplier; an instruction that needs either is then refused, never guessed.
var context = new MappingContext(glamState, glamVault, glamSigner,
    proxyProgram -> authorities.get(proxyProgram),
    request -> resolvers.supply(request));

// One instruction: mapping throws nothing of its own, every outcome is a result. (An
// Error from the caller's lookup or supplier propagates.)
switch (mapper.map(instruction, context)) {
  case MapResult.Mapped mapped -> send(mapped.instruction());
  case MapResult.Passthrough passthrough -> send(passthrough.instruction()); // not proxied
  case MapResult.Unsupported unsupported -> log(unsupported.reason(), unsupported.message());
}

// A whole transaction: maps every instruction first, then rebuilds the transaction with
// each mapped instruction in its place. The rebuild keeps the fee payer, the recent
// blockhash and the lookup tables (a v0 transaction that holds none comes back legacy),
// and for a v1 transaction its version and its settings; it is unsigned, and when nothing
// mapped the caller's own object comes back. Throws UnsupportedInstructionException at
// the first refusal, naming its position (an instruction that references a lookup-table
// account the transaction did not load is one: `unreadable_instruction`), and sava's own
// exception for a transaction the mapped instructions cannot form (a v1 transaction
// pushed past 64 accounts, say; each replacement is checked, so a form at or near a limit
// can be refused on the way). A legacy or v0 rebuild is not size-checked: a mapped
// instruction is usually longer than the target's (GLAM's add at least the state, vault
// and signer seats), so check the result against the packet limit before sending.
var mappedTransaction = mapper.mapTransaction(transaction, context);

// Whether the mapper holds a document for a program, before building anything: the
// document, or null.
var document = mapper.documentOf(program);
```

A `MapResult.Mapped` carries the proxy instruction, the target program, the entry's name
and the proxy instruction's name. A `Passthrough` carries the caller's own `Instruction`
object, the program, the entry's name when a `passthrough` entry matched (else null) and
the reason: the entry's, or "the program has no mapping document". An `Unsupported` carries
the program, the entry's name when one matched, an
[`UnsupportedReason`](ix-proxy/src/main/java/systems/glam/ix/proxy/UnsupportedReason.java)
and a message that says what refused it:

| Reason | When |
| --- | --- |
| `unknown_instruction` | the program has a document, but no entry's discriminator prefixes the data |
| `refused_instruction` | the entry is `unsupported`; the message is the document's reason |
| `account_count` | the instruction leaves out an account the entry needs |
| `account_expectation` | an account is not the one the entry expects at its position |
| `account_privilege` | a forwarded account's signer privilege disagrees with its seat (at a position whose IDL leaves the signer to the caller, only an unsigned account at a signing seat) |
| `remaining_accounts` | accounts beyond the listed positions, on an entry that forbids them |
| `context` | the context supplies no address for a dynamic account the entry seats or expects (a null state, vault or signer; for the integration authority, no lookup, a null answer, or a lookup that threw), or no accounts for an entry that lists supplied ones (no supplier, a null answer, or a supplier that threw) |
| `supplied_accounts` | the supplier answered with the wrong number of accounts, or a null one |
| `unreadable_instruction` | the instruction itself cannot be read: a data span outside its buffer, or an account a transaction left unresolved |

An instruction of a program with no document passes through, unless it cannot be read: that
program is not proxied.

## Supplied accounts

An entry lists **supplied accounts** when the proxy reads accounts from its remaining
accounts that the target instruction never carries. In GLAM's staging documents, Orca's
four liquidity handlers read the price oracles of the pool's two mints and, optionally, a
SOL/USD oracle, and Loopscale's `update_strategy` the strategy's market. The mapper asks
the context's supplier once per such instruction, after the position and seat checks and
before the remaining-accounts check (so a supplier can be asked for an instruction that is
then refused), with a
[`SuppliedAccountsRequest`](ix-proxy/src/main/java/systems/glam/ix/proxy/SuppliedAccountsRequest.java):
the proxy and target programs, the entry and proxy instruction names, the roles in the
order the accounts are inserted, each with the addresses found at its `of` positions (the
mints an oracle is for), and the instruction itself. The supplier answers with the
accounts in that order, the required ones first, and may leave out a trailing run of
optional ones; the answer is copied once inside the same `try` as the call, so a list that
throws while being read counts as a supplier failure (`context`). The accounts are
inserted after the seats and before the accounts beyond the list, read-only and unsigned.
The supplier interprets the roles; the mapper does not.

## Mapping documents

A document names its environment, its target program (`program_id`) and its proxy program
(`proxy_program_id`), and lists the target program's instructions with a **disposition**
each:

- `map`: the proxy has an instruction for it; `handler` names it, `source_accounts`
  describes the target instruction's account list (flags, optionals, expectations),
  `destination_accounts` the proxy's seats, each `dynamic`, `static` or `source` (forwarded
  from a source position), `supplied_accounts` lists what the context supplies after the
  seats (a role, the source positions whose addresses the supplier receives with it,
  whether it may be left out), and `remaining_accounts` (`{"kind": "any"}` or `{"kind":
  "none"}`; absent means `any`) says whether accounts beyond the list may ride along;
- `passthrough`: the instruction is sent as it is, with the reason;
- `unsupported`: the proxy refuses it, with the reason.

The parser ([`MappingDocumentParser`](ix-proxy/src/main/java/systems/glam/ix/proxy/MappingDocumentParser.java))
admits only JSON text (RFC 8259 in well-formed UTF-8, surrogate escapes paired, nesting at
most 64 deep, nothing after the document) holding a well-formed document: an unknown field
or label, a field named twice, a `schema_version` other than 1, a discriminator that is a
prefix of another's, a seat that forwards a position read-only which the target instruction
declares writable, supplied accounts on an entry with a seat a client may leave out or
naming an optional position, and every other shape the rules forbid are refused at load
with a message that says where (the label, the program, the entry) and what is wrong.
Numbers are binary64 values, as JavaScript reads them (`1.0` is the integer 1); positions
and seat indexes must be non-negative integers in `int` range, discriminator bytes 0 to
255. A document built from the records (`MappingDocument`, `InstructionEntry`, …) takes the
field checks in each record's constructor and the checks across entries when a mapper is
created over it. The parser's messages are the document contract's, shared with the
TypeScript mapper; where it refuses more than the contract, and in the record
constructors' checks, the message is this library's own, and `MappingDocumentParserTests`
pins the parser's extra rows.

The full format is specified by the README of the public
[ix-mapper-ts repository](https://github.com/glamsystems/ix-mapper-ts#readme) and pinned by
the conformance cases under its `test/data/cases`. A document can be written by hand for a
proxy that takes the target's instruction data unchanged behind its own discriminator,
seats its caller-side accounts from the four dynamic names, takes any extra accounts
read-only after its seats, and passes the remaining accounts on as the caller gave them.
GLAM's are generated from the target and proxy IDLs by
[idl-src-gen](https://github.com/sava-software/idl-src-gen), which derives the seats by
aligning the two account lists and takes the expectations, the appended static seats and
the supplied roles from a reviewed configuration.

## Using it for another proxy

The parser, the mapper and the document format carry nothing of GLAM's beyond one
vocabulary: the names a document may use for a dynamic seat or an expectation
([`DynamicAccountName`](ix-proxy/src/main/java/systems/glam/ix/proxy/DynamicAccountName.java):
`glam_state`, `glam_vault`, `glam_signer`, `integration_authority`) and the
[`MappingContext`](ix-proxy/src/main/java/systems/glam/ix/proxy/MappingContext.java) that
supplies their addresses. Both are fixed. A proxy family whose caller-side accounts fit
three per-mapping accounts plus one lookup per proxy program can reuse these names as they
are; one that needs another name needs a change to the document contract in both mappers
and their conformance cases, a new `MappingContext` component (a breaking change to its
canonical constructor) and a branch in the mapper's resolution. Every other rule applies as
it is: the document says which positions are forwarded, dropped or expected and which seats
are fixed addresses, and the context supplies the dynamic and the supplied accounts. Of the
conformance cases, 32 describe their own synthetic documents and 26 map against GLAM's
production or staging documents.

## Build & tests

```shell
./gradlew check
```

The tests read GLAM's generated documents, the conformance cases and the mapping vectors
from the tracked [`ix-mapper-ts/`](ix-mapper-ts/README.md) directory, in the layout of the
GLAM monorepo's `packages/glam/ix-mapper-ts` package. The monorepo's public-sync workflow
writes it in a commit that names the monorepo commit it came from, and writes the same
documents into `glam-sdk-java` and the public
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
that one on every entry; they prove agreement, not correctness. The mutation suite leaves
`MapperVectorsTest` out for a measured reason: the vectors kill no mutant the cases do not,
and they multiply the run time. `MapperVectorsTest` skips only a tree with neither vectors
nor a document listing supplied accounts (an older package checkout passed as
`-PglamMappingsDir`); it fails when the documents list supplied accounts and the vectors
directory is missing or empty. The **documents** themselves are read by
`BundledDocumentsTest`: each file admits, is named for its program and filed under its
environment, and every sentinel seat rewrites an absent optional to the proxy program, so a
document the monorepo generates and this parser refuses fails this repository's CI on its
sync commit.

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

Conventional commits on `main` drive release-please under `always-bump-patch`, so a
breaking change is named with a `Release-As: x.y.0` footer on a commit that changes a file
of the package (release-please splits commits by path and drops one that touches nothing
outside the excluded `ix-mapper-ts/` tree, an empty commit included); a tagged release
publishes `systems.glam:ix-proxy` to GitHub Packages (`publish-gh.yml`). A consumer takes a
document change and the library release that reads it together (see the note on
`schema_version` and unknown fields above).
