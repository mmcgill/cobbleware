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
Minecraft auth code. Player sessions last 30 days, are persisted below `COBBLEWARE_GAME_DIR`, and
are not marked `Secure` because the API supports plaintext HTTP.

The Main Worker stores user programs in the Minecraft game directory. It expects
`COBBLEWARE_GAME_DIR` to be set to the Minecraft game directory (e.g. obtained by
`FabricLoader.getGameDir()`). If not supplied at startup it falls back on the CWD,
but this is primarily to facilitate local testing.

See [Main Worker API](../../docs/api.md) for API documentation.

## Future Work / TODO

[ ] Ship Cobbleware/flatbuffers libs with Edge Runtime so programs don't have to bundle them.
[ ] Experiment with `deno bundle` for minifying before building ESZip, to minimize file size.

