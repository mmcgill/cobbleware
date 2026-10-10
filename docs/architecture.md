# Architecture

The mod comprises:

* a Fabric-based Minecraft mod (hereafter "the Mod") responsible for all new blocks, items, in-game mechanics, etc.
* a customized [Edge Runtime](https://github.com/supabase/edge-runtime/) (hearafter "the Runtime") for running
  all user-supplied typescript code in isolated sandboxes.
* a Browser UI served by the Runtime and used by Players to upload programs and monitor executions.

The Mod bundles Main Worker and Event Worker ESZips, and compiles a pinned GitHub Release tag,
asset names, and SHA-256 checksums for native Runtime binaries. It downloads the correct supported
platform binary only when a verified copy is absent, then stages verified immutable artifacts below
the world directory's `cobbleware-data/runtime` directory and starts its managed Runtime on loopback.
Staging, download, and process readiness checks run on a dedicated background thread, so a slow
download never blocks the Minecraft server thread. Until the managed Runtime is ready, the Mod holds
execution delivery and tells Players who use Runtime-dependent commands to try again shortly.
The Mod creates a new in-memory Mod API key for every managed Runtime start; it never writes that
key to disk. An ordinary development JAR has no packaged worker/runtime manifest, so the default
runtime-management mode leaves a manually run Runtime untouched across server restarts.

The Mod is server-only, and uses Polymer to introduce new block types and items without
requiring a client-side mod install.

The Runtime is the [Edge Runtime](../mc-edge-runtime/) with minor modifications/extensions
(like native Windows support). The Runtime runs a Main Worker that serves APIs targetted
at the Mod and at Players, and is responsible for managing the User Workers that run player-supplied
programs.
The Runtime also runs an Event Worker that receives various observability-related events from User Workers
and forwards them to the Main Worker for dispatch.

Throughout the documentation and code, references to "the Runtime" should be understood
as references to the Main Worker running in the Runtime, unless specifically noted otherwise.

All communication between the Mod and the Runtime is initiated by the Mod, which makes requests
to an API served by the Runtime. The Runtime never initiates connections in the opposite direction.
Likewise, all Browser-Runtime connections are Browser-initiated.

The Main Worker is written in Typescript; its code is located in [typescript/main-worker](../typescript/main-worker/).

The Event Worker is likewise written in Typescript; its code is located in [typescript/event-worker](../typescript/event-worker).

The Browser UI is HTML/CSS/Typescript; its code is located in [typescript/browser-ui](../typescript/browser-ui).

The [cobbleware library](../typescript/cobbleware/) is a Typescript library containing type definitions and code
useful for implementing Cobbleputer Programs.

The [Cobbleware Mod](../java/cobbleware) is a Fabric mod written in Java.

## Protocol Buffer Schemas

The Mod and Runtime depend on shared representations of key data structures. These are modeled
as Protocol Buffer schemas found in the [protobuf](../protobuf/) directory. Scripts are used to
generate Typescript and Java code from these schemas to ensure that data can be efficiently
transmitted between the Mod and Runtime, and saved/loaded to persistent game state, in a
consistent and backward-compatible way.

## Executing Programs

The Mod is responsible for:

* queueing events to be processed by each Cobbleputer
* durably maintaining the state of each Cobbleputer
* applying effects to the state of the world

The Runtime is responsible for executing Programs.

Program execution should be "fair" in the sense that no single player's Cobbleputers
may starve the Cobbleputers of another player. We do **not** provide fairness
for individual Cobbleputers. One CPU-bound Cobbleputer may delay the execution of the owning player's
other Cobbleputers.

To ensure player-level fairness, the Mod starts the Runtime with:

* a `--policy` of `per_worker`
* a `--max-parallelism` of `1`
* an `EDGE_RUNTIME_WORKER_POOL_SIZE` equal to the combined size of the
  [guaranteed and best-effort execution pools](./mod.md#execution-pools)
   plus a small fixed number for validating programs at upload time and retrieving program start states

Each player with executing Cobbleputers gets one V8 isolate at at a time, which has its own
OS-level thread, and that isolate processes events for all Cobbleputers executing that player's programs.
Scheduling across players is therefore pre-emptive and managed by the OS scheduler, whereas
scheduling across one player's executions is cooperative and controlled by the player's V8 isolate.
