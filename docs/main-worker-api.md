# Main Worker API

The Main Worker serves an API with some endpoints intended for use directly by the Mod, and other endpoints
intended for use directly by players on the server.

## Transport Security

The Main Worker serves all API endpoints over plaintext HTTP. It does not have direct support for HTTPS/TLS.

This obviously has **significant security implications**. In general, the network path from an arbitrary client
to the Main Worker API over the internet is an **untrusted network path**. It should be assumed that
an attacker can both read and modify TCP packets in both directions.

Consequently:

* The Mod API **must not** be called by e.g. a server administrator over the internet using the shared secret.
  * The Mod API is for use **exclusively** by the Mod, which is running in a different process **on the same host**.
  * As a server administrator running the Mod, you are responsible to ensure that malicious processes on your
    host cannot sniff network traffic on the host. A malicious process with permission to sniff network traffic
    could trivially discover the shared secret and subsequently perform any action that could be performed
    by the Mod.
* An attacker that is able to observe the unencrypted traffic to the Player API will be able to view program
  metadata and upload arbitrary programs to be run in any player's V8 isolate.
  * From the player standpoint, this is a significant vulnerability.
  * From a server administrator standpoint, player code should already be treated as untrusted code.
    The Mod architecture is based on the Edge Runtime specifically to prevent untrusted code from
    compromising the integrity, security or availability of the server.

For improved security, server operators are **strongly encouraged** to host the Main Worker API behind a reverse proxy
configured to perform TLS termination. Some popular Minecraft hosting providers (e.g. PebbleHost) explicitly support this capability.

## Authentication and Authorization

Mod API endpoints are authenticated via the shared secret passed to the Runtime from the Mod as the
`COBBLEWARE_API_KEY` environment variable at startup. The Mod supplies this value as an
`Authorization: Bearer <key>` header.

Player API endpoints and protected HTML content are authenticated with an opaque HttpOnly
`cobbleware_session` cookie. The cookie is intentionally not marked `Secure`, because the API supports
plaintext HTTP. Sessions last 30 days from creation, are persisted across Runtime restarts, and are not
extended by ordinary requests.

The canonical authentication page is `/auth.html`. A request for protected HTML without a valid session
receives `302 Location: /auth.html`. A request for a protected API endpoint without a valid session
receives `401` with a `WWW-Authenticate` header pointing to `/auth.html`.

## Player API

The Player API is intended for direct use by Players, typically via a web browser acting as the user agent.

### Authenticate

The unauthenticated `POST /playerapi/v1/authenticate` endpoint accepts:

```json
{
    "code": "XAU1-98B1"
}
```

When the Runtime has previously received a successful `/modapi/v1/authentication` request for the code,
it responds with `201`, no response body, and a `Set-Cookie` header. The cookie has `Path=/`, `HttpOnly`,
`SameSite=Lax`, and `Max-Age=2592000`.

The Runtime forgets the pending code after the first successful exchange. Pending codes expire after five
minutes and are held in memory only; they do not survive a Runtime restart. Malformed, unknown, expired,
or already-consumed codes receive `400` with a JSON body containing an `"error"` property.

The authenticated `GET /playerapi/v1/whoami` endpoint returns `200` with a JSON body containing the profile name
associated with the current session:

```json
{
    "playerProfileName": "<Minecraft profile name for logged-in player>"
}
```

The endpoint does not extend the session lifetime. Calls without a valid session cookie receive `401` with the
Player API `WWW-Authenticate` header. Other methods receive `405` with `Allow: GET`.

### Session management

The authenticated `POST /playerapi/v1/logout` endpoint accepts no request body. It requires a same-origin
`Origin` header, removes the current session from the durable authentication store, and returns `204` with no
response body. The response also clears the session cookie:

```http
Set-Cookie: cobbleware_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0
```

The cookie name is the configured session cookie name. The cookie deletion is browser-side cleanup; the server-side
session is invalidated as part of the same operation, so the old cookie cannot be used for subsequent
authentication. Calls without a valid session cookie receive `401` with the Player API `WWW-Authenticate` header.
Requests without the required same-origin `Origin` header, or with a different origin, receive `403` with a JSON
error body. Other methods receive `405` with `Allow: POST`.

### Programs

All program operations require player authentication via session cookie.

#### Upload

The `POST /playerapi/v1/programs` operation uploads a new program, replacing the player's active program if one
exists. The request body is a
`multipart/form-data` body with the following parts:

* `program`: required, the ESZip bundle. The part should use `application/octet-stream` as its content type.
* `name`: optional UTF-8 text containing the program name.

The caller supplies the dropped file as the `program` part and its filename as the multipart filename.

If `name` is omitted, the Runtime uses the multipart filename as the program name. Any directory components
are removed, and the filename extension is retained. For example, a file named `hello-world.eszip` gets the
program name `hello-world.eszip`. If no name is supplied, the `program` part must have a non-empty filename.
An explicitly supplied name takes precedence over the filename.

Program names are display metadata and are not used as storage paths. A name must be non-empty after trimming,
must be no longer than 128 Unicode characters, and must not contain NUL or other control characters.
Duplicate program names are allowed; program ids are the unique identifiers.

The Runtime stores the uploaded bytes without validating the ESZip structure; validation is deferred until the
program is loaded for execution. It enforces the player's program quota, configured by the
`COBBLEWARE_PLAYER_PROGRAM_QUOTA_BYTES` environment variable and defaulting to 10 MiB. Because a player has at most
one active program, quota usage is the size of that active program; the quota remains a maximum size for the active
program. The `sizeBytes` value and quota usage count only the ESZip payload, not multipart encoding overhead.

Replacement is performed in a fault-tolerant order. The Runtime first attempts to remove any program files for the
player that are not referenced by `programs.json`, to recover leftovers from an earlier failed replacement. It then
stores the new program, updates `programs.json` so that its `programs` array contains the new program metadata, and
finally deletes the old program file. `programs.json` is the source of truth for which program is active; its
`programs` array contains zero or one record. If a later replacement step fails, a subsequent upload repeats the
cleanup of files not referenced by `programs.json`. In particular, a failed old-file deletion does not change which
program is active.

The upload is not required to leave the program directory free of unreferenced files after every failure, but an
unsuccessful upload does not make a newly stored program active unless `programs.json` is updated successfully.

On success, the Runtime returns `201` with the created program's metadata and a `Location` header:

```http
Location: /playerapi/v1/programs/abc123
Content-Type: application/json; charset=utf-8
```

```json
{
    "id": "abc123",
    "name": "hello-world.eszip",
    "uploadedAt": "2026-08-30T14:25:00Z",
    "sizeBytes": 12345
}
```

`uploadedAt` is an RFC 3339 timestamp in UTC. Upload failures use a JSON body containing an `error` property:

* `400` for a malformed multipart request or missing required part
* `403` for a request rejected by the browser-facing cross-origin request policy
* `413` when the bundle exceeds the player's quota
* `415` when the request is not `multipart/form-data`
* `409` when another program operation for the player is already in progress

The program id is the uppercase, unpadded RFC 4648 Base32 encoding of the SHA-256 hash of the exact uploaded
bytes. It is therefore 52 characters long and uses only `A-Z` and `2-7`. Re-uploading identical bytes for the
same player while that program is active returns `200` with its existing metadata; it does not consume additional
quota or replace its name.

#### List

The `GET /playerapi/v1/programs` operation returns a JSON body listing metadata about the user's active program
on the server. The `programs` array contains zero or one item. The response includes the player's quota usage:

```json
{
    "programs": [
        {
            "id": "abc123",
            "name": "hello-world.eszip",
            "uploadedAt": "2026-08-30T14:25:00Z",
            "sizeBytes": 12345
        }
    ],
    "quotaBytes": 56789,
    "usedBytes": 45678,
    "remainingBytes": 11111
}
```

The `id` field is a content-derived, opaque identifier. Program files are stored at
`$COBBLEWARE_GAME_DIR/cobbleware-data/programs/{playerUUID}/{program-id}.eszip`, with metadata stored in that player's
`programs.json` manifest. A player can see only their own program. The Player API does not provide an endpoint for
downloading program contents. Files not referenced by `programs.json` are not active and may be removed during a
later upload's recovery step.
Listing returns `409` with a JSON `error` property if another program operation for the same player is in progress.
The Player API does not provide an operation for deleting or deactivating the active program; uploading a replacement
is the only way to change it.

#### Start states

The `GET /playerapi/v1/programs/{id}/startStates` operation returns the names of the authenticated Player's active
program start states. The `{id}` path component must identify that active program. A successful response is:

```json
{
    "startStates": ["default", "paused"]
}
```

If the Player has no active program, or `{id}` is not the active program id, the operation returns `404`. It returns
`422` with a JSON `error` property when the active program cannot provide a valid `/startStates` response. Other
methods receive `405` with `Allow: GET`.

Because these operations authenticate with a cookie and are intended for browser use, the Runtime validates
that upload and logout requests have a same-origin `Origin` header. Cross-origin requests are rejected
with `403`; the API does not rely solely on the cookie's `SameSite` attribute for CSRF protection.

### Program Step

The authenticated `POST /playerapi/v1/programs/{id}/step` operation allows a Player to test one invocation of the
state transition function for their active program. The `{id}` path component must identify the Player's currently
active program. A program that is not active, a program belonging to another Player, or a syntactically invalid
program id is not available through this endpoint and receives `404`.

Unlike the other Player API endpoints, this endpoint uses Protocol Buffers rather than JSON for its request and success
response bodies. It supports both the binary protobuf wire format and the canonical
[ProtoJSON](https://protobuf.dev/programming-guides/json/) representation. The binary format is the default: a request
with an `application/octet-stream` media type (parameters are permitted) contains a protobuf `StepRequest` body. The
Player should send a message containing:

* `execution_id`: the UUID associated with the simulated execution
* `state`: the opaque serialized input state
* `event`: the `Events.Event` message describing the event to supply to the transition function; its `event` `oneof`
  selects the concrete event message

The Main Worker invokes the active program's `POST /nextState` Program API endpoint with that request. On success,
the Main Worker returns `200` with an `application/octet-stream` protobuf `StepResponse` body containing the next
state and effects. The state and effects are returned to the caller only: they are not persisted, and effects are not
applied to the Minecraft world. The `execution_id` identifies the simulated input for the invocation; this operation does
not require that a corresponding Cobbleputer exists.

#### Protobuf JSON representation

A Player may instead send the `StepRequest` as ProtoJSON by using an `application/json` request media type (parameters
are permitted). To receive the successful `StepResponse` as ProtoJSON, the Player sends `Accept: application/json`.
The response has an `application/json` media type. Without that explicit selection, the Main Worker returns the binary
`application/octet-stream` response described above. Request and response representation are selected independently, so
a Player may use either representation for each direction.

The JSON representation is the canonical Protobuf JSON representation for the messages in
[`protobuf/program.proto`](../protobuf/program.proto) and its imported schemas. In particular, field names use
lower camel case, `bytes` fields use padded standard base64 strings, 64-bit integer fields use decimal strings, and a
`oneof` is represented by its selected field directly in the containing object.

For a JSON request, the Main Worker parses the JSON as `StepRequest` and serializes it before invoking the program.
Malformed JSON, fields not recognized by the schema, or values that cannot be represented by the message receive `400`
with a JSON `error`. The Main Worker does not otherwise validate application-level requirements in the message; as with
the binary representation, the program receives the resulting request and is responsible for interpreting it.

For a JSON success response, the Main Worker parses the program's `200` response as a binary `StepResponse` before
serializing it as ProtoJSON. A `200` response that does not have the binary protobuf media type or cannot be parsed as
`StepResponse` receives `502` with a JSON `error`. Responses with another status continue to be returned from the
program unchanged.

Binary protobuf request bodies and responses from player code are untrusted opaque bytes. When the binary
representation is used, the Main Worker checks the HTTP request framing and media type, but does not unpack or verify
either body. It forwards the request bytes unchanged to the active program's per-Player user worker, where the program
may unpack them, and forwards the program response status, headers, and bytes unchanged to the Player. The JSON
representation requires the conversion described above. Malformed or malicious binary data can cause the user worker
or the Player's decoder to fail or hang; the per-Player isolate limits request processing to the owning Player's
program.

Failures generated while validating the Player API HTTP request use the normal JSON error shape containing an `error`
property. A non-`200` response produced by the player program is returned as-is, including a program-produced `500`
`ErrorResponse`; the Main Worker does not verify that response body. If the Main Worker cannot invoke the user worker,
it returns the usual JSON `500` error.

The endpoint has the following response behavior:

* `200` with a protobuf `StepResponse` in the representation selected by the Player when the transition succeeds
* `400` with a JSON `error` when the request body cannot be read or a JSON request cannot be parsed as `StepRequest`
* `401` with the Player API `WWW-Authenticate` header when the session is missing or invalid
* `403` with a JSON `error` when the request is missing a same-origin `Origin` header or has a different origin
* `404` when the requested program id is invalid, there is no active program, or the id is not the active program
* `405` with `Allow: POST` for other methods
* `415` with a JSON `error` when the request media type is neither `application/octet-stream` nor `application/json`
* `502` with a JSON `error` when a JSON success response cannot be converted from the program's binary `StepResponse`
* the active program's response status, headers, and body for non-`200` responses when the user worker returns a
  response; the Main Worker does not verify the response's protobuf body
* `500` with a JSON `error` when the Main Worker cannot invoke the user worker

Because this operation is authenticated with a cookie and is intended for browser use, it requires the same-origin
`Origin` check used by upload and logout. Although a step does not mutate persistent Cobbleputer state, running a
program can consume runtime resources, so the check is still required.

## Mod API

### Player Authentication

The authenticated `POST /modapi/v1/authentication` endpoint accepts:

```json
{
    "code": "XAU1-98B1",
    "playerUUID": "<UUID>",
    "playerProfileName": "<profile name>"
}
```

The Mod calls this endpoint when the player executes the `/cobbleware auth XAU1-98B1` slash command.
The code may be supplied with or without its presentation hyphen and is normalized case-insensitively.
The documented code format is eight uppercase alphanumeric characters, displayed as `XXXX-XXXX`.
The Runtime treats `playerUUID` as an opaque value supplied by the Mod and does not validate that it has UUID
syntax. It normalizes the value to lowercase before using it as the player's identity and program-directory
component.

If the code is new, or is retried for the same player UUID, the endpoint returns `201` with no body.
If the code is already pending for another player UUID, it returns `400` with a JSON response body
containing an `"error"` property. The Runtime trusts `playerProfileName` as supplied by the Mod and does
not validate its contents.

### Program Query

The authenticated `GET /modapi/v1/players/{playerUUID}/program` endpoint queries the given player's
currently active program and its start states. A successful response requires that the given player has
previously uploaded a valid program that correctly serves start states. On success, the response has the
shape:

```json
{
    "id": "abc123",
    "name": "hello-world.eszip",
    "uploadedAt": "2026-08-30T14:25:00Z",
    "sizeBytes": 12345,
    "startStates": {
        "<stateName>": [1,2,....]
    }
}
```

The metadata fields have the same meanings and formats as the Player API's program metadata. In the
case where the given player has not uploaded a program, the response is a 404.

In the case where the player has uploaded a program but that program does not return a valid
`/startStates` response, the response status is 200 (because the request to the Mod API was successful)
and the response body has the shape:

```json
{
    "programError": "..."
}
```

The program error is intended to be passed along to the player that uploaded the program.
It may be a message indicating a problem with the program's `/startStates` response,
a stacktrace from the program itself, etc.

### Execution Sync

The `POST /modapi/v1/executions` operation is the engine of Cobbleputer execution. The Mod
calls this operation at regular intervals whenever there are new events or in-progress
computations. The request body is described by the `cobbleware.internal.ExecutionsRequest` message.
The response body is described by `cobbleware.internal.ExecutionsResponse`.

The request and success response both use the binary protobuf wire format with an
`application/octet-stream` media type (parameters are permitted). Results are ordered to match the
request's `executions` list. Errors produced by this endpoint use the same media type and contain
a `cobbleware.internal.ErrorResponse` message with an `error` string.

The operation fulfills several purposes:

* The Mod presents the Runtime with its current understanding of in-progress and pending work,
  in the form of per-execution state/event sequence pairs. The Mod's representation of work
  is authoritative.
* The Runtime synchronizes with the Mod's presented view, possibly dispatching new events
  to new or existing per-player user workers.
* The Runtime's response provides the Mod with the Runtime's own representation of in-flight work,
  conveying new execution states and Effect sequences to the Mod for storage and processing.

The Runtime maintains an in-memory data structure (non-authoritative and not persisted to disk)
that tracks the current state of every active execution, from its point of view.
It contains, for each active execution:

* The `execution_id` that uniquely identifies the execution. A new execution (with a fresh id)
  begins when a Cobbleputer is started or restarted.
* The current execution state and associated event sequence number. Event sequence numbers are
  specific to an execution and increase monotonically.
* A bounded queue of events to be processed.
* A bounded queue of effects emitted by the execution and not yet acknowledged by the Mod.

The Runtime currently hard-codes an event-window limit of 10 events per execution. If the Mod's
authoritative snapshot contains more pending events, the Runtime retains only the first 10. Once the
Mod accepts a consumed prefix and advances its in-memory queue, a later sync fills the newly
available window slots from the remaining suffix. The Mod must therefore retain the suffix until the
Runtime reports it consumed.

The Runtime also hard-codes an outbox limit of 50 unacknowledged effects per execution. When an
invocation emits effects after its outbox has reached that limit, the Runtime drops those effects.
Only retained effects receive sequence numbers, so the effects returned to the Mod remain contiguous.

#### Execution records

An execution is the unit of identity presented to a program and synchronized by this operation;
the API does not separately track a Cobbleputer identity. An execution is owned by one player for
its entire lifetime. The `player_id` in `ExecutionState` identifies that player, allowing the
Runtime to select the player's active program and per-player user worker. A start or restart creates
a new `execution_id`; ownership or program changes therefore also require a new execution.

While a Cobbleputer is active, the Mod's driver holds the authoritative snapshot for its execution.
The Cobbleputer block entity durably stores its execution id, owner id, lifecycle state, current state,
and sequence counters, but pending events and received effects are driver memory only. The active snapshot
contains:

* its `execution_id`, owner `player_id`, and lifecycle `state`;
* the state immediately before its first pending event, and that event's sequence number;
* its bounded, ordered in-memory queue of pending events; and
* effects returned by the Runtime for immediate best-effort application, together with the last
  durable effect sequence number for which application was attempted.

The first event and effect of an execution have sequence number 1. Sequence numbers increase by one
without wrapping. `ExecutionState.event_seq_num` is the next event sequence number even when its
`events` list is empty. Its events occupy the contiguous range beginning at that number.
`ExecutionState.effect_seq_num` acknowledges every effect through that number; it is zero before
the first effect is applied. `EXECUTION_STATUS_UNSPECIFIED` is invalid. `RUNNING` permits dispatch,
`STOPPED` preserves work without dispatching it, and `CRASHED` is terminal for the execution id.
Every `ExecutionState` must include its required scalar/message values; each event must select an
`Event.event` oneof. `event_seq_num` must be at least one. Empty states and empty event lists are valid.

The Runtime keeps a non-durable cache, keyed by `execution_id`. Each entry stores the immutable
owner id; lifecycle state; the current state and last consumed event sequence number; a bounded,
ordered event queue; an ordered outbox of unacknowledged effects with their sequence numbers; and,
when applicable, the single in-flight program invocation. The entry also has a generation token so
that a result from an invocation cancelled by a re-sync cannot update a newer entry. The Runtime
uses the execution id as `StepRequest.execution_id` when it invokes the player program.

`ExecutionResult.event_seq_num` is the last event consumed by the Runtime, and `final_state` is the
state after that event. A failing event is consumed but leaves the state unchanged. The result's
effects occupy the contiguous range beginning at `effect_seq_num`; they are all effects that have
not been acknowledged by the request. When `effects` is empty, `effect_seq_num` is the next effect
sequence number. A result for a newly created execution with no consumed events reports event
sequence number 0.

#### Runtime synchronization algorithm

An `ExecutionsRequest` is a complete snapshot: it contains every execution that may still need
execution or may have unacknowledged effects. The Mod serializes sync calls and never changes an
event after assigning its sequence number. For each request, the Runtime:

1. Rejects duplicate execution ids and records with a missing required value. For an existing id,
   a different `player_id` is invalid; changing ownership requires a fresh execution id.
2. Removes every cached entry absent from the request, increments its generation token, and abandons
   its queued or in-flight work. For each new execution, initializes the cache from the Mod's state
   and event snapshot. For an existing execution, the Runtime uses the sequence number and state
   supplied by the Mod to discard a prefix that the Mod has accepted, retains compatible
   completed work, and appends the previously unseen contiguous event suffix. If the supplied state
   or overlapping events are incompatible with the cache, it discards the cache and reinitializes
   it from the Mod's authoritative snapshot.
3. Drops from each effect outbox every effect through the request's `effect_seq_num`. This is an
   acknowledgement of an application attempt, not an assertion that the effect was applicable.
4. For a `RUNNING` execution with no invocation in flight, schedules the oldest queued event for the
   owning player's worker. The invocation is asynchronous: this operation need not wait for it, so
   a response may describe only work completed before the response snapshot. On success, the Runtime
   advances the state and event sequence number, assigns consecutive sequence numbers to the returned
   effects, and schedules the next event. It ignores a completion whose generation token is stale.
5. Does not dispatch events for `STOPPED` executions. On an invocation failure, including an invalid
   program response, it consumes the failing event, marks the execution `CRASHED`, and discards its
   remaining local event queue. A `CRASHED` execution never resumes under the same execution id and
   discards subsequently presented events.
6. Returns one `ExecutionResult` for every requested execution, containing its current state,
   lifecycle state, last consumed event sequence number, and remaining effect outbox.

When the Mod receives a result for the same active Cobbleputer block entity and execution id, it updates
the durable returned state and lifecycle state, removes the consumed prefix of its in-memory event queue,
and attempts returned effects in sequence order. A `CRASHED` result also makes the Mod discard its remaining
in-memory event queue. The Mod durably advances its acknowledged effect sequence after each application
attempt, but does not persist received effects before attempting them. If a Cobbleputer becomes inactive,
or if the server stops, while events or effects are in flight, that work can be lost; duplicate or missed
effect attempts are acceptable under this best-effort model.

The endpoint returns a protobuf `ErrorResponse` for errors generated by the Main Worker:

* `400` for an unreadable/malformed protobuf payload or an invalid execution snapshot
* `401` with `WWW-Authenticate: Bearer` when Mod authentication fails
* `405` with `Allow: POST` for other methods
* `415` for a request whose media type is not `application/octet-stream`
* `500` for an unexpected Main Worker failure

Program invocation failures are represented in the normal `200` `ExecutionsResponse`: the failing
event is consumed and its execution becomes `CRASHED`. Crash diagnostics are not included in this API.
