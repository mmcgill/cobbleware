# Cobbleware Event Worker

The Event Worker consumes Edge Runtime User Worker events and best-effort relays
player-attributable execution logs to the Main Worker.

`COBBLEWARE_API_KEY` must match the Main Worker's Mod API key.
`COBBLEWARE_MAIN_WORKER_URL` is the HTTP or HTTPS base URL used to reach the Main
Worker and defaults to `http://127.0.0.1:9000`. Set it when the Runtime listens on
a non-default port. `COBBLEWARE_LOG_RATE_PER_SECOND` sets each player's program-log
token refill rate and defaults to 2; buckets hold at most 100 tokens.

The worker uses an authenticated WebSocket to `/modapi/v1/logs`. It reconnects after
disconnects but does not buffer messages while unavailable, so logs are observability
data rather than durable execution input.
