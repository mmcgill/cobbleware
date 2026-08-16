# Architecture

The mod comprises:

* a Fabric-based Minecraft mod (hereafter "the Mod") responsible for all new blocks, items, in-game mechanics, etc.
* a customized [Edge Runtime](https://github.com/supabase/edge-runtime/) (hearafter "the Runtime") for running
  all user-supplied typescript code in isolated sandboxes.

The Mod bundles native binaries for the Runtime, and is responsible for unpacking the correct
platform-specific version and maintaining the Runtime process.

## IPC

The execution model for the code running on Mainframe blocks in the Mod is fundamentally asynchronous
and decoupled from Minecraft server tick rate. Events are buffered during a tick, and then invoked
as a batch. Similarly, each tick the Mod polls (in non-blocking fashion) for up to N results,
and processes those results.

The Runtime presents the Mod with a Protobuf-over-HTTP interface designed to support batch processing
of asynchronously executing functions:

`POST /invokeBatch` allows the Mod to start an arbitrary number of asynchronous invocations, and returns immediately.
`POST /poll` allows the Mod to retrieve results.

The Runtime also presents the Mod with an endpoint that can be used to trigger installation of new code
for a Mainframe:

 `POST /installFunction`

The Runtime API is served unauthenticated/unencrypted on localhost.