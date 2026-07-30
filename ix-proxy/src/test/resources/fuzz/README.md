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
and the three mapped-output properties it asserts). Seeds, named for the
path they pin:

- `payer-replace` / `payer-keep` — the payer proxy's two outcomes (the
  keep seed rotates the account pool so the fee payer lands at the payer
  index).
- `identity-fixed` / `identity-variable` — pass-through on each lookup shape.
- `rewrite-variable` — a discriminator-and-data rewrite.
- `unknown-program` / `payer-no-accounts` / `short-discriminator` — the
  pass-through and rejection edges.

Findings become a named seed here **and** a regression test; each committed
corpus is replayed inside `check` by its generated `*FuzzSeedReplayTest`.
