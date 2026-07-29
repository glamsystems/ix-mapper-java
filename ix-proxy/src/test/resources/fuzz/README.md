# Fuzz seed corpora

## mappingConfig

Arbitrary bytes parsed as mapping-config JSON, exactly as
`ProgramMapConfig.createProxies` parses each downloaded `*.json` file:
`JsonIterator.parse(bytes)` then `ProgramMapConfig.parseConfig` (see
`MappingConfigFuzz`). Malformed-input contract: garbage in ->
`RuntimeException` out. Seeds:

- `system.json` — the system-program mapping config (inlined, matches the
  `glam/mapping-configs-v1` shape).
- `token.json` — a real multi-instruction config from the download.
- `empty-object` / `no-instructions` — minimal shapes pinning the
  empty-and-absent-instruction paths.

Findings become a named seed here **and** a regression test; the committed
corpus is replayed inside `check` by the generated
`MappingConfigFuzzSeedReplayTest`.
