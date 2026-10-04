# Event Worker

The Event Worker handles observability-related events for Cobbleputer executions
in a distinct V8 isolate from the Main Worker, in order to avoid blocking Main Worker
progress.

## Event Types

The Event Worker translates and forwards the following events:

* `BootFailure` events are forwarded as `ExceptionLogEntry`.
* `UncaughtException` events are forwarded as `ExceptionLogEntry`.
* `Shutdown` events, when the shutdown `reason` is `CPUTime` or `Memory`, are forwarded
  as `LimitExceededLogEntry`.
* `Log` events are forwarded as `ProgramLogEntry`.

All other events are discarded.

## Imposed Limits

`Log` events are rate-limited via the token bucket algorithm. Each player has a logging token
bucket that receives tokens at a configurable rate (with a default of 2 tokens per second) up
to a limit of 100 tokens. Each forwarded `Log` event consumes a token.

The `message` field of each `Log` event is capped at 10KB in UTF8 representation; longer messages
are truncated.

## Configuration and Delivery

The Event Worker connects to the Main Worker's `/modapi/v1/logs` and `/modapi/v1/log-stats` endpoints with the
`COBBLEWARE_API_KEY` credential. The bundled Runtime WebSocket client cannot attach
an `Authorization` header, so the Event Worker presents that key in the Main Worker's
reserved `cobbleware.bearer.<base64url-key>` WebSocket subprotocol instead. `COBBLEWARE_MAIN_WORKER_URL` identifies the
Main Worker's HTTP or HTTPS base URL and defaults to `http://127.0.0.1:9000`; it must
be set explicitly when the Runtime uses a different port or a reverse-proxy path
prefix. `COBBLEWARE_LOG_RATE_PER_SECOND` configures the logging token refill rate and
defaults to 2; it must be a non-negative finite number.

`/modapi/v1/logs` carries only displayable execution log entries. `/modapi/v1/log-stats` carries the current
per-player program-log token buckets; the Main Worker merges them into PlayerStats for the Browser statistics stream.

The Event Worker reconnects after a Main Worker WebSocket disconnection. It does not
buffer events while disconnected or while a prior send cannot be accepted, so those
events are dropped. Observability events never block the Event Worker's event-consumer
loop or Main Worker progress.
