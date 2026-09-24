# ix-mapper-ts (synced)

The mapping documents of the GLAM instruction mapper, in the layout of the TypeScript
package `packages/glam/ix-mapper-ts` in the GLAM monorepo: the generated documents under
`src/generated/mapping/{production,staging}` and the conformance cases under
`test/data/cases`.

The GLAM monorepo's public-sync workflow writes this directory, in a commit that names the
monorepo commit it came from; the same workflow publishes the package to
`glamsystems/ix-mapper-ts`. The first copy was made by hand from that repository at
16320bf. Nothing under `src/` or `test/` here is edited by hand (this file is): a document
or case changes in the monorepo and arrives with the next sync.
