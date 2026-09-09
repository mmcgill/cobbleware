# Cobbleware Mod

This document describes configuration and usage for the Cobbleware mod itself, together with
design details for how the Mod facilitates program execution.

## Slash Commands

### Authentication

```
/cobbleware authenticate <code>
```

This command is run with a `<code>` provided to the user through their web browser. It serves
to tie a browser session to an already-authenticated Player logged into the Minecraft server.

### Program

```
/cobbleware program
```

Prints information (e.g. name, size, names of start states) about the player's currently active program, if
there is one.

### Starting Execution

```
/cobbleware exec <start-state>
```

Starts executing the current program with the given start state on the closest Cobbleputer.
Prints confirmation on success.
Prints an appropriate error message if no program can be started because:

* there is no Cobbleputer within 3m, or
* the nearest Cobbleputer is Running, or
* the player has no active program, or
* the named start state does not exist

## Program Execution Design

A placed Cobbleputer block has an associated block entity that stores:

* execution ID: `null` when the block state is `UNINITIALIZED` or `CRASHED`, and reset to a fresh UUID
  whenever a program is started on the Cobbleputer from a start state; not reset when a `STOPPED`
  Cobbleputer is resumed.
* event sequence num: a `long` that starts at 0 and increments for each event enqueued.
* effect sequence num: a `long` that starts at 0 and advances for each effect processed.
* state: `null` when execution ID is null, otherwise a byte array holding the current execution state

Breaking the Cobbleputer block destroys the block entity; effectively terminating the execution.

Execution state is durable as long as the execution does not crash and the Cobbleputer block is not broken.
Events and Effects, on the other hand, are ephemeral and best-effort. There is no guarantee that every
event will be processed, and there is no guarantee that every effect will be applied.

The heart of the Mod is the `ExecutionDriver` singleton. It is owned by the main server thread.

It presents an API for enqueueing events by cobbleputer block entity, assigning each event a sequence id
and incrementing the block entity's sequence id in the process. Events are dropped whenever the
cobbleputer is not in a `RUNNING` state.

It presents an API for activating/deactivating Cobbleputer block entities. That API is used both by
handlers for placing/breaking of Cobbleputer blocks and by handlers for chunk loading/unloading,
to ensure that the `ExecutionDriver` has references to all active Cobbleputer block entities.

It manages at most one in-flight async request to the `/modapi/v1/executions` endpoint.
At most once per tick (via `ServerTickEvents.END_SERVER_TICK`), the `EventDriver` does the following:

* If there is an in-flight `/modapi/v1/executions` request with no response:
  * Do nothing further.
* If there is a response to a previously issued `/modapi/v1/executions` request:
  * Apply all effects in the response.
  * Pop all events from queues whose event sequence numbers are smaller than the `event_seq_num` in the response.
  * Update active Cobbleputer block entities with new states/sequence numbers.
    * If a Cobbleputer block entity goes inactive before in-flight events are processed, the resulting state is discarded.
* Construct and send a new `/modapi/v1/executions` request.
  * Include up to `MAX_OFFERED_EVENTS_PER_EXECUTION` events for each active Cobbleputer.