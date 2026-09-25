# Cobbleware Mod

This document describes configuration and usage for the Cobbleware mod itself, together with
design details for how the Mod facilitates program execution.

## Configuration

The Mod reads `config/cobbleware.json` when the server starts. In addition to its Main Worker
connection settings, it accepts these positive integer settings:

* `instrumentationWindowTicks`, which defaults to 600 and controls the number of server ticks
  retained for the in-memory instrumentation window.
* `maxRunningExecutionsPerPlayer`, which defaults to 10 and caps the number of a player's loaded
  Cobbleputer executions that can be `RUNNING` across all dimensions. Existing configuration files
  that omit either setting receive its default; zero, negative, fractional, or non-numeric values
  make the configuration invalid.

Starting or resuming an execution is rejected when it would exceed the running-execution cap. When a
chunk falls below Minecraft's `BLOCK_TICKING` status, the Mod immediately removes a running execution
in that chunk from the cap. On the next normal server tick, it stops the execution and records that it
should be resumed when the chunk becomes block-ticking again. That later transition avoids persisting
block-entity changes from the chunk-status callback. The Mod admits a later resume only if the owner has
capacity, queues a `Resumed` event on success, and otherwise leaves the Cobbleputer stopped and sends its
online owner a chat error. Non-ticking, stopped, and crashed executions do not count toward the cap;
pausing or making a running execution non-ticking releases its slot.

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
the closest Stopped or Crashed Cobbleputer within 3m. Resuming a Stopped
Cobbleputer continues its existing execution; resuming a Crashed Cobbleputer starts a
fresh execution with its stored state. Selection is identical to
`/cobbleware exec`. Only the Player associated with that execution may use
either command. Each command prints confirmation on success and an appropriate
error message when there is no Cobbleputer within 3m, the nearest Cobbleputer is
not in the required lifecycle state, or its execution belongs to another Player.
Right-clicking a Cobbleputer does not change its lifecycle state.

## Program Execution Design

A placed Cobbleputer block has an associated block entity that stores:

* Cobbleputer ID: a UUID generated when the block entity is created and durable
  until that block is broken. It identifies the physical Cobbleputer for
  instrumentation and administrative commands, independently of executions.
* execution ID: `null` when the block state is `UNINITIALIZED` or `CRASHED`, and reset to a fresh UUID
  whenever a program is started or a crashed Cobbleputer is restarted; not reset when a `STOPPED`
  Cobbleputer is resumed.
* owner player ID: `null` when the block state is `UNINITIALIZED`; otherwise the ID of the Player whose
  active program owns the execution or, for a `CRASHED` Cobbleputer, the execution that can restart it.
* owner player name: `null` when the block state is `UNINITIALIZED`; otherwise the name
  captured when the execution starts, which permits offline execution owners to
  be selected by the instrumentation command.
* event sequence num: a `long` holding the next event sequence number. It starts at 1 and increments
  after each event is enqueued.
* effect sequence num: a `long` that starts at 0 and advances for each effect processed.
* state: `null` when the block state is `UNINITIALIZED`, otherwise a byte array holding the current
  execution state. A crashed Cobbleputer retains the state before its failing event.
* stopped-at tick: `null` unless the Cobbleputer is `STOPPED`; when present, the game tick at
  which it was stopped, used to populate the next `Resumed` event.
* was-running-when-unloaded: whether a `STOPPED` Cobbleputer should receive one automatic resume
  attempt after its chunk becomes block-ticking again. A persisted `RUNNING` state is also always treated
  as a pending automatic resume, which covers worlds that predate this field and server shutdown paths
  that save before the chunk stops block ticking.

Cobbleputers created before the durable Cobbleputer ID field receive an
in-memory UUID while loading. The Mod marks that migration dirty during normal
server-tick work after chunk attachment, never from a block-entity loading or
attachment callback.

Breaking the Cobbleputer block destroys the block entity; effectively terminating the execution.
It also immediately unpairs every loaded Wireless Observer paired to that Cobbleputer. Unloaded
Observers retain their pairing until the next safe validation finds that the Cobbleputer no longer exists.

Execution state is durable as long as the Cobbleputer block is not broken. When an execution crashes, its
old execution ID and sequence counters are discarded, while its owner and state remain available to restart
the Cobbleputer. That restart creates a fresh execution ID and resets its sequence counters.
Events and Effects, on the other hand, are ephemeral and best-effort. There is no guarantee that every
event will be processed, and there is no guarantee that every effect will be applied.

The currently emitted lifecycle events are `Started` after `/cobbleware exec` or after a crashed Cobbleputer
is restarted with `/cobbleware resume`, and `Resumed` after its owner resumes a stopped Cobbleputer or a
Cobbleputer in a newly block-ticking chunk is successfully admitted back to running. The driver retains at most 10 pending events
per active Cobbleputer and offers at most 10 to the Runtime in each execution-sync request. Currently `EmitBeep` sends nearby players a
`beep` system-chat message and `SendChat` sends its message to those same nearby players; neither effect
has audio or durable delivery semantics yet.

The heart of the Mod is the `ExecutionDriver` singleton. It is owned by the main server thread.

It presents an API for enqueueing events by cobbleputer block entity, assigning each event a sequence id
and incrementing the block entity's sequence id in the process. Events produced while a Cobbleputer
is `STOPPED` are not enqueued; its existing pending events remain queued for when it resumes.

It presents an API for activating/deactivating Cobbleputer block entities. That API is used by
execution commands and by full-chunk block-ticking transitions, to ensure that the `ExecutionDriver`
has references only to Cobbleputers in block-ticking chunks.

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
when the target is unloaded, unavailable, or has a full event queue. Events produced while the
target is stopped are not enqueued and are not replayed when it resumes.

Breaking a Cobbleputer unpairs all currently loaded observers indexed for its global position.
That index is held by the Mod, not by a Cobbleputer block entity. An unloaded paired observer is
validated after it, or its target chunk, next loads: if the target dimension is unavailable or its
already-loaded target position has no Cobbleputer block entity, the observer clears its pairing and
resets its power to zero. An unloaded target chunk is not treated as missing and is never loaded just
to validate a pairing. Validation is queued from chunk callbacks and runs on a normal server tick,
because persisting the clear pairing from chunk attachment would call `setChanged` and can deadlock.
No `WirelessObserverUnpaired` event is delivered for Cobbleputer destruction, because the target
execution is being terminated or is already absent.

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

`SetRedstonePower` effects apply only to loaded observers currently paired to the
emitting Cobbleputer. An effect with neither selector changes all of those observers;
`observer_id` and `observer_color` independently narrow that set, and both selectors
must match when both are supplied. Unspecified or unrecognized selected colors are
ignored rather than treated as an unfiltered effect.

## Instrumentation

The Mod records a configurable sliding window of in-memory instrumentation.
`instrumentationWindowTicks` in `config/cobbleware.json` sets the number of
server ticks retained; it defaults to 600 ticks (30 seconds at 20 ticks per
second). Existing configuration files without the setting use that default.
The data is discarded at server shutdown.

The recorded measurements are:

* full wall-clock duration and outcome of each execution-sync request, measured
  from dispatch until its `CompletableFuture` completes;
* events included per execution-sync request, per player and physical Cobbleputer;
* accepted and dropped events, per player, physical Cobbleputer, and event type;
* attempted and failed effect applications, plus processing duration, per
  player, physical Cobbleputer, and effect type; and
* the current count of loaded running and stopped Cobbleputers, per player.

An event is accepted when it enters the Cobbleputer's pending queue. A failed admission is
recorded as dropped, except that an event produced while the Cobbleputer is `STOPPED` is not
an admission and is recorded as neither accepted nor dropped. An effect is attempted whenever
the Mod calls its existing effect-application path and is failed only when that
path throws. Metrics do not alter the existing best-effort event/effect
semantics. The events-per-sync measurement is recorded when the Mod dispatches an
execution-sync request, including zero-event execution records.

At the end of every server tick, the Mod finalizes that tick's raw player-attributable instrumentation
and offers one `ExecutionStatsTick` protobuf message to its long-lived Main Worker WebSocket. A player
entry preserves the existing per-Cobbleputer and event/effect-type measurements and state gauge; it is
not aggregated over players, Cobbleputers, types, or the Mod's retention window. The message has a
process-local tick sequence number and capture time. Its `used_bytes` and `max_bytes` fields remain zero;
the Main Worker sets those fields only in the player-scoped Browser WebSocket copy. The Mod does not send the server-wide
execution-sync duration/outcome metric because it cannot be attributed to an individual player.

Stats streaming is best-effort. The server tick never waits for a connection or a send; ticks produced
while the Main Worker WebSocket is unavailable, or while its prior send is pending, are discarded. The
client reconnects with backoff. This does not change execution, event, effect, or Mod instrumentation
behavior. The configurable `instrumentationWindowTicks` continues to govern the Mod's retained data and
the `/cobbleware stats` command only; Browser UIs retain their own fixed 600 received-tick window.

```
/cobbleware stats
/cobbleware stats <playername>
/cobbleware stats <event>
/cobbleware stats <cobbleputer id>
```

The `stats` command (which requires permission level 2 or higher) shows statistics aggregated
over the instrumentation window, either in general or for a specific player/event/Cobbleputer.
Player names, event names (for example `chat-message`), and Cobbleputer UUIDs
are disjoint qualifier forms. The unqualified view includes all metric
families; a player view contains that player's metrics, an event view contains
that event's counters, and a Cobbleputer view contains its event, effect, and
events-per-sync metrics and reports its dimension and block position. The command offers known
qualifiers as tab completions. The unqualified view contains only total summary
counts; qualified views contain their relevant breakdowns. Metric rows use
Minecraft's built-in uniform font and ordinary-space padding for readable column
alignment in the command output.
