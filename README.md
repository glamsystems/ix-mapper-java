# ix-mapper-java [![Gradle Check](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml/badge.svg)](https://github.com/glamsystems/ix-mapper-java/actions/workflows/build.yml) [![Publish Release](https://github.com/glamsystems/ix-mapper-java/actions/workflows/publish-gh.yml/badge.svg)](https://github.com/glamsystems/ix-mapper-java/actions/workflows/publish-gh.yml)

Rewrites Solana instructions into their GLAM proxy-program equivalents. A vault's delegate
builds an instruction the way any client of the native program would, and the mapper turns
it into the GLAM instruction that performs it through the vault: the proxy program adds its
access checks before and after forwarding the request to the native program.

The rules are data, not code: one **mapping document** per source program and environment,
generated upstream from the native and the proxy IDLs and published through the
[ix-mapper-ts](https://github.com/glamsystems/ix-mapper-ts) repository. The TypeScript package
there and this library read the same documents, and the conformance cases that ship with them
are tested against both.

## Usage

```java
// Every document of one environment, one file per source program.
var documents = MappingDocuments.readDirectory(Path.of("mapping/production"));
var mapper = InstructionMapper.createMapper(documents);

// What a mapping needs from the caller: the vault's GLAM accounts. The mapper derives no
// address; the integration authority of a proxy program is supplied by the caller too.
var context = new MappingContext(glamState, glamVault, glamSigner, proxyProgram -> authorities.get(proxyProgram));

// One instruction: throws nothing of its own, every outcome is a result (an Error from the
// integration-authority lookup propagates).
switch (mapper.map(instruction, context)) {
  case MapResult.Mapped mapped -> send(mapped.instruction());
  case MapResult.Passthrough passthrough -> send(passthrough.instruction()); // GLAM does not proxy it
  case MapResult.Unsupported unsupported -> log(unsupported.reason(), unsupported.message());
}

// A whole transaction: every instruction is mapped first, then the mapped ones are
// replaced in place over the fee payer, the recent blockhash, the table objects the
// transaction holds and (for a v1 transaction) its version and settings; the rebuild is
// unsigned, and when nothing mapped the caller's own object comes back. Throws
// UnsupportedInstructionException at the first refusal, naming its position, and sava's
// own exception for a transaction the mapped instructions cannot form (for example a v1
// transaction pushed past 64 accounts; each replacement is checked, so a form at or near
// a limit can be refused on the way).
var mappedTransaction = mapper.mapTransaction(transaction, context);
```

A `MapResult.Mapped` carries the proxy instruction, the source entry's name and the handler's
name; a `Passthrough` the caller's own `Instruction` object and the reason; an `Unsupported`
the [`UnsupportedReason`](ix-proxy/src/main/java/systems/glam/ix/proxy/UnsupportedReason.java)
and a message that names the account or rule that refused it. An instruction that cannot be
read at all (a data span outside its buffer, an account a transaction left unresolved) is an
`Unsupported` result too, never an exception, whether or not its program has a document.

## Mapping documents

A document names its environment, its source program and its proxy program, and lists the
source program's instructions with a **disposition** each:

- `map`: the proxy program has a handler; `handler` names it, `source_accounts` describes the
  native instruction's account list (flags, optionals, expectations), `destination_accounts`
  the handler's seats, each dynamic (a GLAM account the context supplies), static (a fixed
  address) or forwarded from a source position, and `remaining_accounts` says whether
  accounts beyond the list may ride along;
- `passthrough`: the instruction is sent as it is, with the reason;
- `unsupported`: GLAM refuses it, with the reason.

The parser ([`MappingDocumentParser`](ix-proxy/src/main/java/systems/glam/ix/proxy/MappingDocumentParser.java))
admits only JSON text (RFC 8259 in well-formed UTF-8, surrogate escapes paired, nesting at
most 64 deep, nothing after the document) holding a well-formed document:
unknown fields, a field named twice, a discriminator that is a prefix of another's, a seat
that forwards a position read-only which the native instruction declares writable, and every
other shape the rules forbid are refused at load time with a message that names the field.
Numbers are binary64 values, as JavaScript reads them (`1.0` is the integer 1); positions,
seats and bytes must fit an int. The cases under `test/data/cases` of the ix-mapper-ts package
are the document contract's tests, and this library runs every one of them; what this parser
refuses beyond them is listed on the tracking issue, not in the code.

## Build & tests

```shell
./gradlew check
```

The tests read the generated documents and the conformance cases from the tracked
[`ix-mapper-ts/`](ix-mapper-ts/README.md) directory, in the layout of the GLAM monorepo's
`packages/glam/ix-mapper-ts` package: the monorepo's public-sync workflow writes it (the same
workflow publishes the package to the
[ix-mapper-ts repository](https://github.com/glamsystems/ix-mapper-ts)); the first copy was
made by hand from that repository at 16320bf. An upstream change is tested here once its
sync lands. To run the suite against a local checkout of the package before that:

```shell
./gradlew check -PglamMappingsDir=/absolute/path/to/ix-mapper-ts
```

The path is resolved against the repository root; the mapping content is a test input, so a
changed tree re-runs the suite rather than serving a cached result.

## Hardening

The `ix-proxy` module registers the PIT mutation suite `pitestIxProxy` and the Jazzer fuzz
targets `fuzzMappingConfig` (the document parser over arbitrary bytes) and `fuzzIxMapper`
(the mapper over arbitrary instructions) via sava-build's hardening feature; unkilled mutants
are ratcheted against the accepted baseline in [ix-proxy/config/pitest](ix-proxy/config/pitest).
See [AGENTS.md](AGENTS.md) for the process contract.
