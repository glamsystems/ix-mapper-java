# Fuzz seed corpora

## mappingConfig

Arbitrary bytes parsed as mapping-config JSON, exactly as
`ProgramMapConfig.createProxies` parses each downloaded `*.json` file:
`JsonIterator.parse(bytes)` then `ProgramMapConfig.parseConfig` then
`createProgramProxies` (see `MappingConfigFuzz`). Malformed-input contract:
garbage in -> `RuntimeException` out, at parse or at proxy construction.
Seeds:

- `system.json` — the system-program mapping config (inlined, matches the
  `glam/mapping-configs-v1` shape).
- `token.json` — a real multi-instruction config from the download.
- `empty-object` / `no-instructions` — minimal shapes pinning the
  empty-and-absent-instruction paths.

## ixMapper

Arbitrary bytes carved into an instruction and driven through
`ProgramProxy.lookupProxy`/`mapInstruction` against a fixed mapper covering
the payer, identity, and full-rewrite proxy shapes over both program-proxy
lookups (fixed-length map and variable-length scan) — the path that faces
user-submitted transactions (see `IxMapperFuzz`, including the carve layout
and the four mapped-output properties it asserts). Seeds, named for the
path they pin:

- `payer-keep` / `payer-replace` — the payer proxy's two outcomes (the
  replace seed rotates the account pool so a non-signer holds the payer
  index).
- `identity-fixed` / `identity-variable` — pass-through on each lookup shape.
- `rewrite-fixed` / `rewrite-fixed-rotated` / `rewrite-variable` — a
  discriminator-and-data rewrite on each lookup shape, the fixed one seating
  a dynamic and a static account.
- `placeholder-swap` — the rewrite's optional-account sentinel: the source
  program id in a placeholder slot becomes the proxy program.
- `short-source-dropped` — program A with one account against a map whose
  missing positions are dropped: the success path of a source shorter than
  its index map.
- `unknown-program` / `payer-no-accounts` / `seated-account-missing` /
  `short-discriminator` — the pass-through and rejection edges.

Findings become a named seed here **and** a regression test; each committed
corpus is replayed inside `check` by its generated `*FuzzSeedReplayTest`.
