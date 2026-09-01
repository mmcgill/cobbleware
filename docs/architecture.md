# Architecture

The mod comprises:

* a Fabric-based Minecraft mod (hereafter "the Mod") responsible for all new blocks, items, in-game mechanics, etc.
* a customized [Edge Runtime](https://github.com/supabase/edge-runtime/) (hearafter "the Runtime") for running
  all user-supplied typescript code in isolated sandboxes.

The Mod bundles native binaries for the Runtime, and is responsible for unpacking the correct
platform-specific version and maintaining the Runtime process.

The Runtime is the [Edge Runtime](../mc-edge-runtime/) with minor modifications/extensions
(like native Windows support). The Runtime runs a Main Worker that serves APIs targetted
at the Mod and at Players, and is responsible for managing the User Workers that run player-supplied
programs.

Throughout the documentation and code, references to "the Runtime" should understood
as references to the Main Worker running in the Runtime, unless specifically noted otherwise.

All communication between the Mod and the Runtime is initiated by the Mod, which makes requests
to a API served by the Runtime. The Runtime never initiates connections in the opposite direction.

The Main Worker is written in Typescript; its code is located in [typescript/main-worker](../typescript/main-worker/).

The [cobbleware](../typescript/cobbleware/) Typescript library contains type definitions and code
useful for implementing Cobbleputer Programs.

## Flatbuffers Schemas

The Mod and Runtime depend on shared representations of key data structures. These are modeled
as Flatbuffer schemas found in the [schema](../schema/) directory. Scripts are used to
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
* an `EDGE_RUNTIME_WORKER_POOL_SIZE` equal to the maximum number of online players supported by the Server

Each online player with executing Cobbleputers gets one V8 isolate at at a time, and that isolate
processes events for all Cobbleputers executing that player's programs.
