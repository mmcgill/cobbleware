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

The Program API contract specifies request and response body formats via FlatBuffers.
The expected media type is `application/octet-stream`.

## Start States

A _start state_ is a named initial state value for Cobbleputer computations.
The name associated with each initial state value is unique within the scope
of a single Program.

A program **must** respond to a `GET /startStates` request from the main worker
with a 200 response whose body is a `StartStatesResponse`.

