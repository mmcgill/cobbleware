# Program API

The Program API is the API presented by a player program, running inside a per-player V8 isolate
(a user worker, in Edge Runtime terminology) to the main worker.

The API is described as an HTTP API because it is implemented via
[Deno.serve](https://docs.deno.com/api/deno/http-server/#Deno.serve).
However, in an Edge Runtime user worker the `Deno.serve` function does not actually involve
the networking stack at all. Instead it acts as a bridge, implemented in rust, between
the main worker V8 isolate and the user worker V8 isolate.

At no point is the user program actually given the ability to open and listen on actual sockets on the host,
and the HTTP protocol is not actually being used. The Edge Runtime is instead directly constructing JS objects
that provide the look and feel of an inbound HTTP request.

## Authentication and Authorization

No authentication or authorization mechanism is needed, because only the main worker
is able to invoke the API.

## Request and Response Body Representation

The Program API contract specifies request and response body formats via Protocol Buffers.
The expected media type is `application/octet-stream`.

For `POST /nextState`, the Main Worker forwards the Player API's protobuf request bytes unchanged. The Main Worker
does not unpack or verify those request bytes; the player program is responsible for unpacking the `StepRequest` inside
its per-Player V8 isolate. Responses from the player program are likewise untrusted: the Main Worker forwards the
response status, headers, and body without unpacking or verifying the protobuf response. Malformed or malicious
request bytes may therefore fail or hang that program, but the isolate contains the effect to the owning Player's
program.

## Start States

A _start state_ is a named initial state value for Cobbleputer computations.
The name associated with each initial state value is unique within the scope
of a single Program.

A program **must** respond to a `GET /startStates` request from the main worker
with a 200 response whose body is a `StartStatesResponse`.

In a valid resonse, the `state_name` of each start state is unique within the response.
A response with repeated `state_name` values is invalid.

## Program Step

The program **must** respond to a `POST /nextState` request with a response
that indicates, for the given program state, the subsequent state and associated
effects.

The request body is a `StepRequest` containing the simulated `execution_id`, the current `state`, an `event` from
the `Events.Event` message, and `effect_limit`. The message's `event` `oneof` identifies the concrete event message.
`effect_limit` is the maximum number of effects the Runtime will accept from that invocation. The program's transition
function receives the state, event, and this limit as its context and returns a `Program.StepResponse` containing the
subsequent state and effects. Effects beyond the supplied limit are dropped.
On success, the response should have 200 status with a `StepResponse` body. On failure (e.g. due to an exception)
the response should have a 500 response with an `ErrorResponse` body.
