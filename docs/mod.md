# Cobbleware Mod

This document describes configuration and usage for the Cobbleware mod itself, together with
design details for how the Mod facilitates program execution.

## Configuration

The Mod reads `config/cobbleware.json` when the server starts. It accepts these optional settings:

* `runtimePort`, which defaults to 9000 and selects the loopback port used by a Mod-managed Runtime.
  It must be an integer from 1 through 65535.
* `instrumentationWindowTicks`, which defaults to 600 and controls the number of server ticks
  retained for the in-memory instrumentation window.
* `maxRunningExecutionsPerPlayer`, which defaults to 10 and caps the number of a player's loaded
  Cobbleputer executions that can be `RUNNING` across all dimensions. Existing configuration files
  that omit any setting receive its default; zero, negative, fractional, or non-numeric values
  make the configuration invalid.
* `maxQueuedEventsPerPlayer`, which defaults to 200 and caps the total ephemeral events queued
  across all of one player's active Cobbleputers. Queued events on stopped Cobbleputers count;
  events intended for stopped Cobbleputers are dropped immediately.
* `maxPendingEffectsPerPlayer`, which defaults to 200 and caps the total number of outstanding effects
  that can be buffered for application before execution steps block.
* `maxTotalExecutionStateBytesPerPlayer`, which defaults to 1,048,576 and caps the sum of all durable
  execution-state byte arrays owned by one player. Stopped, crashed, and unloaded records count; protobuf
  storage overhead, events, and effects do not.
* `guaranteedPoolSize`, a positive integer that defaults to the max server player limit and sets the
  size of the guaranteed execution pool.
* `bestEffortPoolSize`, a non-negative integer that defaults to zero and sets the size of the
  best-effort execution pool.

The Mod always starts the Runtime and creates a fresh in-memory API key to secure
communication between the Mod and the Runtime.

Starting or resuming an execution is rejected when it would exceed the running-execution cap. When a
chunk falls below Minecraft's `BLOCK_TICKING` status, the Mod immediately removes a running execution
in that chunk from the cap. On the next normal server tick, it stops the execution and records that it
should be resumed when the chunk becomes block-ticking again. That later transition avoids persisting
block-entity changes from the chunk-status callback. The Mod admits a later resume only if the owner has
capacity, queues a `Resumed` event on success, and otherwise leaves the Cobbleputer stopped and sends its
online owner a chat error. Non-ticking, stopped, and crashed executions do not count toward the cap;
pausing or making a running execution non-ticking releases its slot.

The Mod publishes execution lifecycle transitions and execution-pool assignment changes to the Main Worker through the
existing execution-log publication connection. Every lifecycle transition carries its owner's current
running-execution usage and configured caps, allowing the Browser UI to promptly update a value it already displays;
the per-tick execution-statistics census remains the authoritative Limits snapshot. A pool entry reports the owner's
resulting `GUARANTEED`, `BESTEFFORT`, `WAITING`, or `NONE` state. The Mod emits it only when that state changes,
including the first assignment, best-effort rotation, and a player becoming ineligible after their last loaded running
Cobbleputer stops. Publication is best-effort and happens only from normal server-tick processing; it does not alter
the chunk-load persistence rule.

For every execution-sync snapshot, the Mod supplies the Runtime with a per-execution `state_size_limit` equal to
the space available after all of that owner's other durable states. The Runtime rejects a program response whose
state exceeds that value before it reaches the Mod. The Mod still verifies each accepted final state against the full
durable owner record set, because separately in-flight executions may each have been offered their own maximum. An
over-limit state is discarded, the Cobbleputer crashes with its prior state retained, and `TotalExecutionStateSize`
is published as a limit-exceeded log entry before the crash transition.

## Slash Commands

### Authentication

```
/cobbleware authenticate <code>
```

This command is run with a `<code>` provided to the user through their web browser. It serves
to tie a browser session to an already-authenticated Player logged into the Minecraft server.

### Execution Priority

At any given time each player has an _execution priority_ that is used to determine which
pool their executions are assigned to. The valid priority values are 1, 2, 3. By default,
online players have priority 2 and offline players have priority 3.

```
/cobbleware setpriority <player> <online|offline> <priority>
```

The `setpriority` command (which requires permission level 2 or higher) can be used to
persistently set the online or offline priority of a specific player.

```
/cobbleware priority [player]
```

The `priority <player>` command can be used to check the online and offline priorities of a player.
Users with permission level 2 or higher can check the priorities of any player.
Users with lower-level permission can check only their own priorities.

### Execution Pool

```
/cobbleware pool [player]
```

The `pool <player>` command displays the current execution pool of the given player.
Users with permission level 2 or higher can check the pool of any player.
Users with lower-level permission can only check their own pool.

```
/cobbleware pools
```

The `pools` command (requires permission level 2 or higher) prints the full membership
of the guaranteed and best-effort execution pools, with output shaped like:

```
Guaranteed (5/5): ...
Best-effort (3/10): ...
```

The Best-effort pool will show all players in the best-effort pool in bold first, followed
by all players in the best-effort _queue_, in order.

### Program

```
/cobbleware program
```

Prints information (e.g. name, size, names of start states) about the player's currently active program, if
there is one.

### Starting Execution

```
/cobbleware start <start-state>
```

Starts executing the current program with the given start state on the closest Cobbleputer.
Prints confirmation on success.
Prints an appropriate error message if no program can be started because:

* there is no Cobbleputer within 3m, or
* the nearest Cobbleputer is Running, or
* the nearest Cobbleputer belongs to another Player, or
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
`/cobbleware start`. Only the Cobbleputer's owner may use either command. Each command prints
confirmation on success and an appropriate error message when there is no Cobbleputer within 3m,
the nearest Cobbleputer is not in the required lifecycle state, or it belongs to another Player.
Right-clicking a Cobbleputer does not change its lifecycle state.

### Cobbleputer Ownership

```
/cobbleware owner
```

The `owner` command identifies the owner (by player name and UUID) of the closest Cobbleputer within 3m.
The command requires permission level 2 or higher.

## Program Execution Design

A placed Cobbleputer block has an associated block entity that stores:

* Cobbleputer ID: a UUID generated when the block entity is created and durable
  until that block is broken. It identifies the physical Cobbleputer for
  instrumentation and administrative commands, independently of executions.
* owner player ID: recorded from the Player who places the block and durable until that block is broken.
  The ID locates that Player's external execution-state file without scanning every Player file.

Cobbleware stores each Player's last observed profile name once in
`cobbleware-data/player-names.json`, keyed by UUID. It refreshes the cache while the Player is online and uses it
for owner display. Cobbleputer block entities deliberately store no player name, so a renamed Player is represented
consistently across all of their Cobbleputers.

The durable execution record is stored in `cobbleware-data/executions/<player-uuid>.pb` in the world directory,
one protobuf file per Player. The file carries a Mod-defined format version and the record's execution ID, sequence
counters, state bytes, stopped-at tick, and pending automatic-resume intent. The execution ID is `null` when
the block state is `UNINITIALIZED` or `CRASHED`, and reset to a fresh UUID whenever a program is started or a crashed
Cobbleputer is restarted; it is not reset when a `STOPPED` Cobbleputer is resumed. A crashed Cobbleputer retains its
owner and state before the failing event so it can restart.

Execution records written by older Cobbleware builds may contain an owner name. Cobbleware accepts those records and
omits the redundant name on the next write; owner display continues to use the UUID-keyed player-name cache.

The Mod writes every Player file by forcing a complete same-directory temporary file and atomically replacing the
previous file. It queues dirty files asynchronously before each world save and synchronously flushes all loaded Player
files during server stopping. Pending events and received effects remain in-memory and best-effort.
Each Player file also stores the owner's shared burst and slow chat-token buckets plus their next Minecraft game-time
refill and transfer ticks. New owners and migrated format-version-one files begin with both buckets full. Since the
schedule uses Minecraft game time, a Mod or server restart preserves the remaining allowance without granting tokens
for time while the server was offline.

Breaking the Cobbleputer block destroys the block entity; effectively terminating the execution.
It also immediately unpairs every loaded Wireless Observer paired to that Cobbleputer. Unloaded
Observers retain their pairing until the next safe validation finds that the Cobbleputer no longer exists.

Execution state is durable as long as the Cobbleputer block is not broken. When an execution crashes, its
old execution ID and sequence counters are discarded, while its owner and state remain available to restart
the Cobbleputer. That restart creates a fresh execution ID and resets its sequence counters.
Events and Effects, on the other hand, are ephemeral and best-effort. There is no guarantee that every
event will be processed, and there is no guarantee that every effect will be applied.

The currently emitted lifecycle events are `Started` after `/cobbleware start`, and `Resumed` when an execution
that was not driven during the preceding scheduler tick is driven again. This includes an owner resuming a stopped
Cobbleputer, a crashed Cobbleputer restarting from its retained state, a Cobbleputer in a newly block-ticking chunk
being admitted back to running, and a Player re-entering an execution pool. `Resumed` is placed ahead of ordinary
pending work. If the owning Player's shared pending-event allowance is full, the oldest pending event for that Player
is dropped to make room. The driver retains at most the configured number of pending events across a player's active
Cobbleputers and offers each active Cobbleputer's full pending queue to the Runtime in each execution-sync request. `SendChat` sends its message to nearby players;
it has no durable delivery semantics.

The heart of the Mod is the `ExecutionDriver` singleton. It is owned by the main server thread.

It presents an API for enqueueing events by cobbleputer block entity, assigning each event a sequence id
and incrementing the block entity's sequence id in the process. The driver maintains a per-player
count of pending events so admission is constant-time, and reconciles that count from the active queues
once per server tick. Events produced while a Cobbleputer is `STOPPED` are not enqueued; its existing
pending events remain queued for when it resumes and count toward their owner's allowance.

It presents an API for activating/deactivating Cobbleputer block entities. That API is used by
execution commands and by full-chunk block-ticking transitions, to ensure that the `ExecutionDriver`
has references only to Cobbleputers in block-ticking chunks.

### Wireless Observers

A placed Wireless Observer stores a randomly generated UUID in its block entity. The UUID is
durable across chunk unloads, reloads, and server restarts, but a replacement observer block gets
a fresh UUID.

### Pairing and Unpairing

Using an unpaired Cobblemeter on an unpaired observer has no effect. Using an unpaired Cobblemeter
on a paired observer pairs the meter to the observer's target exactly as if the meter had been used
on that Cobbleputer; it therefore requires the normal Cobbleputer-pairing permission. The target
must already be loaded: this interaction never loads a chunk just to inspect ownership.

Using a paired Cobblemeter on an unpaired observer pairs that observer with the meter's target and
emits a `WirelessObserverPaired` event, provided the two blocks are in the same dimension and at
most 64 blocks apart. Any Player may do this. Using it on an observer already paired to that same
target unpairs the observer, resets its power level to zero, and emits a
`WirelessObserverUnpaired` event; only the target Cobbleputer's owner or a Player with permission
level 2 or higher may do so. A paired meter used on an observer paired to a different Cobbleputer
has no effect.

Breaking or otherwise replacing a paired observer emits `WirelessObserverUnpaired` to its target
before its block entity is removed; chunk unloading does not emit that event. These lifecycle events
are best-effort just like block-state changes: the pairing remains durable, while delivery is dropped
when the target is unloaded, unavailable, or its owner's queued-event allowance is full. Events
produced while the target is stopped are not enqueued and are not replayed when it resumes.

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
A powered Wireless Observer only emits signal out its back, in the direction opposite its facing, and only weakly
(i.e. via `getWeakRedstonePower`).

`SetRedstonePower` effects apply only to loaded observers currently paired to the
emitting Cobbleputer. An effect with neither selector changes all of those observers;
`observer_id` and `observer_color` independently narrow that set, and both selectors
must match when both are supplied. Unspecified or unrecognized selected colors are
ignored rather than treated as an unfiltered effect.

### Execution Pools

The _eligible player set_ is the set of players for which there is at least
one running loaded Cobbleputer.

Whenever the eligible player set changes or a player joins/leaves the server,
the Mod recomputes membership in the guaranteed
execution pool by sorting the eligible player set according to the triple
(player priority, online time, random number) and assigning players to the guaranteed
pool in order until it is full. When a player is added to the guaranteed pool,
they are automatically removed from the best-effort pool/queue if necessary.

All eligible players not in the guaranteed pool are rotated through the best-effort pool.
The _best-effort queue_ holds eligible players waiting to enter the best-effort pool.
When a player enters the best-effort pool, the driver sets a server tick countdown.
Every tick, players in the best-effort pool whose server tick countdown (defaulting to
20 * 5 server ticks, roughly 5 seconds) has expired
are removed from the pool and placed at the back of the best-effort queue.
When the best-effort pool has empty slots, players are polled from the front of the
best-effort queue.

When processing a sync request, the driver will attempt to process executions for
players even when those players are no longer in either execution pool, as long as
the Cobbleputers associated with the executions are still loaded. Execution
states are stored like usual and effects are applied. Execution resutls for executions whose Cobbleputers
are no longer loaded are discarded.

### Execution Syncs

The `ExecutionDriver` manages at most one in-flight async request to the `/modapi/v1/executions` endpoint.
At most once per tick (via `ServerTickEvents.END_SERVER_TICK`), the `ExecutionDriver` does the following:

* If there is an in-flight `/modapi/v1/executions` request with no response:
  * Do nothing further.
* If there is a response to a previously issued `/modapi/v1/executions` request:
  * Apply all effects in the response.
  * Pop all events from queues whose event sequence numbers are less than or equal to the `event_seq_num` in the response.
* Update active Cobbleputer execution records with new states/sequence numbers.
    * If a Cobbleputer block entity goes inactive before in-flight events are processed, the resulting state is discarded.
* Construct and send a new `/modapi/v1/executions` request.

The driver constructs an `ExecutionsRequest` that contains all the active executions
of all the players in the guaranteed and best-effort pools.
For each included player it advances that owner's durable chat buckets using the current game time and supplies the
current burst and slow counts. The Main Worker only conveys these values to program steps. When processing a returned
`SendChat` effect, the Mod atomically spends a burst token before delivery and drops the effect if none is available;
all other effects are unaffected.

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

An event is accepted when it enters the owner's shared pending-event allowance. A failed admission is
recorded as dropped when that allowance is full, except that an event produced while the Cobbleputer is `STOPPED` is not
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
Each PlayerStats record also carries the Mod-authoritative maximum capacities for its burst and slow chat buckets.

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

## Runtime Management

To keep the size of the Mod manageable, the Mod does not bundle the Runtime binaries.
Instead, an architecture-specific Runtime release is downloaded on startup and placed
in `cobbleware-data/runtime/{os}-{arch}/{sha}`. The Mod is built and tested
against a specific Runtime release version. That version and the SHA of the binary
for each supported OS/arch are built into the Mod. Shipping a new Runtime release
cannot (by design) be done transparently without server admin awareness.
It requires a new Mod release.

Bundled ESZips of the main and event worker code _are_ bundled in the Mod.
These are extracted at startup to `cobbleware-data/workers`, replacing
the existing files if there are any.

The Mod is responsible for managing the process lifecycle of the Runtime.
Downloading and starting the runtime process happen asynchronously and do
not block server start-up. The server will still start if a problem is encountered,
and programs will not execute until the problem is resolved.

## Local Development and Testing

```bash
gradlew runServer
```

The `gradlew runServer` task builds the Mod and starts a development server.
The main and event workers are bundled and placed on the classpath.
By default, the bundling is done using the edge runtime release pinned in
`build.gradle` (see `runtimeRelease`), and the server will likewise download
and run that runtime release. Local development on the Mod and workers does not
require a rust toolchain and local runtime build.

For development tasks that involve changes to the runtime itself, a developer
can force the use of the locally built edge runtime in the `mc-edge-runtime` submodule
with

```bash
gradlew runServer -PuseLocalRuntime
```

The local `mc-edge-runtime` checkout will be built, if necessary, and used by the Mod as
the Runtime.
