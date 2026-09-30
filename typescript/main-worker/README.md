# Cobbleware Main Worker

The Main Worker is responsible for managing Cobbleputer programs, state, and execution.
It runs as a privilged V8 isolate in the Edge Runtime. Cobbleputer programs are executed
in their own V8 isolates, with CPU/memory limits and other sandbox controls managed by
the Main Worker.

The Main Worker presents an authenticated HTTP API to the Mod. Mod API calls are authenticated
via a shared secret supplied to the Main Worker at startup via the `COBBLEWARE_API_KEY` env var
and expected to be supplied as a bearer token. A Mod API request that does not have the expected
authorization header receives a 401 response.

Player API calls use an HttpOnly `cobbleware_session` cookie created by exchanging a short-lived
Minecraft auth code. Player sessions last 30 days, are persisted below `$COBBLEWARE_GAME_DIR/cobbleware-data`, and
are not marked `Secure` because the API supports plaintext HTTP.

The Main Worker stores user programs in the Minecraft game directory at
`$COBBLEWARE_GAME_DIR/cobbleware-data/programs/{playerUUID}/{program-id}.eszip`, with per-player metadata stored alongside the program
files. Program ids are uppercase, unpadded RFC 4648 Base32 encodings of the SHA-256 hash of the exact program
bytes. It expects
`COBBLEWARE_GAME_DIR` to be set to the Minecraft game directory (e.g. obtained by
`FabricLoader.getGameDir()`). If not supplied at startup it falls back on the CWD,
but this is primarily to facilitate local testing. The per-player program quota is configured with
`COBBLEWARE_PLAYER_PROGRAM_QUOTA_BYTES` and defaults to 10 MiB.

For repository development, `deno task start` sets `COBBLEWARE_GAME_DIR` to
`java/cobbleware/run/world`, so the bundled Main Worker shares the development server's
`cobbleware-data` directory.

The per-player User Worker memory limit is configured with
`COBBLEWARE_PLAYER_MEMORY_QUOTA_BYTES` and defaults to 256 MiB. It must be a positive whole
number of MiB. The Main Worker passes this limit to Edge Runtime for every User Worker and reports
it as the maximum in the Player execution-statistics stream.

See [Main Worker API](../../docs/api.md) for API documentation.

The bundle task first builds the Browser UI from `../browser-ui` into its
generated `static/` staging directory, then tree-shakes the Main Worker with
Deno's esbuild-powered bundler before creating `main-worker.eszip`. The
Main Worker JavaScript is intentionally not minified.

`deno task integrate` is separate from the unit-test task. It bundles the Main
Worker, starts the local Edge Runtime, and verifies the execution-statistics
path from the Mod API to an authenticated Player WebSocket.

## Future Work / TODO

[ ] Ship Cobbleware/protobuf libraries with Edge Runtime so programs don't have to bundle them.
