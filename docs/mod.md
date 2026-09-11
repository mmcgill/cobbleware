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

### Pausing and Resuming Execution

```
/cobbleware pause
/cobbleware resume
```

`pause` stops the closest Running Cobbleputer within 3m, and `resume` restarts
the closest Stopped Cobbleputer within 3m. Selection is identical to
`/cobbleware exec`. Only the Player associated with that execution may use
either command. Each command prints confirmation on success and an appropriate
error message when there is no Cobbleputer within 3m, the nearest Cobbleputer is
not in the required lifecycle state, or its execution belongs to another Player.
Right-clicking a Cobbleputer does not change its lifecycle state.

## Program Execution Design

A placed Cobbleputer block has an associated block entity that stores:

* execution ID: `null` when the block state is `UNINITIALIZED` or `CRASHED`, and reset to a fresh UUID
  whenever a program is started on the Cobbleputer from a start state; not reset when a `STOPPED`
  Cobbleputer is resumed.
* owner player ID: `null` when execution ID is null; otherwise the ID of the Player whose active program
  owns the execution.
* event sequence num: a `long` holding the next event sequence number. It starts at 1 and increments
  after each event is enqueued.
* effect sequence num: a `long` that starts at 0 and advances for each effect processed.
* state: `null` when execution ID is null, otherwise a byte array holding the current execution state
* stopped-at tick: `null` unless the Cobbleputer is `STOPPED`; when present, the game tick at
  which it was stopped, used to populate the next `Resumed` event.

Breaking the Cobbleputer block destroys the block entity; effectively terminating the execution.

Execution state is durable as long as the execution does not crash and the Cobbleputer block is not broken.
Events and Effects, on the other hand, are ephemeral and best-effort. There is no guarantee that every
event will be processed, and there is no guarantee that every effect will be applied.

The currently emitted lifecycle events are `Started` after `/cobbleware exec` and `Resumed` after its owner
uses `/cobbleware resume`. The driver retains at most 10 pending events per active Cobbleputer and offers
at most 10 to the Runtime in each execution-sync request. Currently `EmitBeep` sends nearby players a
`beep` system-chat message and `SendChat` sends its message to those same nearby players; neither effect
has audio or durable delivery semantics yet.

The heart of the Mod is the `ExecutionDriver` singleton. It is owned by the main server thread.

It presents an API for enqueueing events by cobbleputer block entity, assigning each event a sequence id
and incrementing the block entity's sequence id in the process. Events are dropped whenever the
cobbleputer is not in a `RUNNING` state.

It presents an API for activating/deactivating Cobbleputer block entities. That API is used both by
handlers for placing/breaking of Cobbleputer blocks and by handlers for chunk loading/unloading,
to ensure that the `ExecutionDriver` has references to all active Cobbleputer block entities.

It manages at most one in-flight async request to the `/modapi/v1/executions` endpoint.
At most once per tick (via `ServerTickEvents.END_SERVER_TICK`), the `ExecutionDriver` does the following:

* If there is an in-flight `/modapi/v1/executions` request with no response:
  * Do nothing further.
* If there is a response to a previously issued `/modapi/v1/executions` request:
  * Apply all effects in the response.
  * Pop all events from queues whose event sequence numbers are less than or equal to the `event_seq_num` in the response.
  * Update active Cobbleputer block entities with new states/sequence numbers.
    * If a Cobbleputer block entity goes inactive before in-flight events are processed, the resulting state is discarded.
* Construct and send a new `/modapi/v1/executions` request.
  * Include up to `MAX_OFFERED_EVENTS_PER_EXECUTION` events for each active Cobbleputer.

### Wireless Observers

A placed Wireless Observer stores a randomly generated UUID in its block entity. The UUID is
durable across chunk unloads, reloads, and server restarts, but a replacement observer block gets
a fresh UUID. Observers created before this field existed receive an in-memory UUID while loading;
the Mod marks that migration dirty during normal server-tick work after chunk attachment, never in
the block entity loading callback.

### Pairing and Unpairing

A successful use of a paired Cobblemeter on an observer emits a `WirelessObserverPaired` event to
the meter's target Cobbleputer. Re-pairing an observer from one target to another emits a
`WirelessObserverUnpaired` event to the old target before emitting `WirelessObserverPaired` to the
new target. Re-pairing to the same target emits only `WirelessObserverPaired`. Breaking or
otherwise replacing a paired observer emits `WirelessObserverUnpaired` to its target before its
block entity is removed; chunk unloading does not emit that event. These lifecycle events are
best-effort just like block-state changes: the pairing remains durable, while delivery is dropped
when the target is unloaded, unavailable, not running, or has a full event queue.

`WirelessObserverPaired` and `WirelessObserverUnpaired` contain the observer color, UUID, facing
direction, and global position. `BlockStateChange` likewise contains the observer UUID, in
addition to its existing color, direction, positions, and old/new block states.

### Block State Changes

A Wireless Observer produces a BlockStateChange event whenever the block type or block state of
the block it faces is changed. After a Wireless Observer block is attached to a loaded chunk, the next server tick
caches the block type and state of the block it faces in its block entity data. The Wireless Observer block
overrides the `updateShape()` neighbor callback and checks the block type/state of the block it faces against
its cached data. If there is a difference, the Wireless Observer registers itself with the `ExecutionDriver`.

When the `ExecutionDriver` runs its per-tick processing, it drains its list of Wireless Observers with updated
blocks. For each one, it compares the cached and current values, emits at most one `BlockStateChange` event to the
paired Cobbleputer, and updates the cached block type/state values. Repeated changes before that processing are
coalesced into the same event. The cache is updated even when the observer is unpaired or delivery is not possible,
so changes are best-effort and are not replayed after a later pairing, resume, or chunk load.

### Redstone Power Level

A Wireless Observer has a redstone power level from 0 to 15, stored in block state.
The Polymer-managed client state only differentiates between powered (level > 0) and unpowered (level == 0).
A powered Wireless Observer only emits signal in the direction it's facing, and only weakly
(i.e. via `getWeakRedstonePower`).