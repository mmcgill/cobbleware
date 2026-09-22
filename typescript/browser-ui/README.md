# Browser UI

This Deno workspace member contains the editable Browser UI source. Its build writes generated
static assets to `../main-worker/static`; that directory is intentionally ignored because
`typescript/main-worker/scripts/bundle.ts` regenerates it before creating `main-worker.eszip`.

Run `deno task check`, `deno task build`, or `deno task verify` from this directory.

`verify` checks local generated references, an unused export removed from a reached ESM module,
stale-output removal, protected/public routing, and the assets served from the real Main Worker
ESZip. It requires a locally built Edge Runtime (or `EDGE_RUNTIME_BIN`).
