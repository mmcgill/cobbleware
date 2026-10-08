# Gameplay

The mod introduces:

* Cobbleware _programs_, immutable ESZip-bundled JS/TS programs uploaded and owned by Players.
* A Cobbleputer ('puter) block for executing programs.
* A Wireless Observer block that acts as both a sensor and a switchable Redstone signal source.
* A Cobblemeter item that can be used to pair Wireless Observers and troubleshoot Cobbleputers.

## Programs

Programs are ESZip-bundled JS/TS programs uploaded by players externally via a browser-based UI.
Programs are not first-class from a gameplay standpoint. They have no explicit
in-game representation as blocks, items or entities. Each player has one active program at a time.
All the Cobbleputer executions started by that player run the player's active program.
When the player uploads a new program, all executions continue executing with their current
states using the new program.

## Cobbleputers

A Cobbleputer block executes Programs. Cobbleputer programs are event handlers.
They are invoked by the 'puter as a result of Events that happen in the Minecraft world, and they can return
Effects to be processed by the Mainframe as output.

When a 'puter block is placed, it is _uninitialized_: it has no state and is not executing a program.
The placed block's _owner_ is the player that placed it.
The owning player can run their active program on a placed 'puter block via the `/cobbleware start <start-state>`
command. An executing 'puter block stores its own runtime state, which persists across
chunk reloads and server restarts.

When an executing 'puter block is broken, its associated execution is terminated, its
execution state is destroyed, and its owner is forgotten. 'puter block items are
indistinguishable from one another.

Only the owning player can pause or resume the execution of a Cobbleputer with
`/cobbleware pause` or `/cobbleware resume`. Those commands select the closest
'puter within 3m, as `/cobbleware start` does.

### Execution Model

A Cobbleputer is single-threaded, and executes events sequentially in the order they are produced.

A 'puter is either `UNINITIALIZED`, `RUNNING`, `STOPPED` or `CRASHED`. A newly placed 'puter
is in the `UNINITIALIZED` state. Its owner can load a program as described above for a 'puter in
any state. On successful load, the 'puter state is set to `RUNNING`. Its owner may pause a
running 'puter with `/cobbleware pause` and resume a stopped
or crashed 'puter with `/cobbleware resume`. If an unhandled exception bubbles up out of a
Program's event handler function, the 'puter enters the `CRASHED` state. Resuming
a crashed 'puter starts a new execution using its most recently stored state.

Each 'puter has a state value that is provided as input to each event handler, and can
be arbitrarily modified by that event handler. Its maximum returned-state size is dynamically
derived from its owner's remaining total execution-state capacity. If an event handler returns a
state value larger than that allowance, the 'puter crashes and the returned state value is discarded.

Each player has one shared, in-memory allowance of 200 queued events by default across all of
their active 'puters. If events are produced faster than that player's programs can process them,
the allowance eventually fills and new events for their running 'puters are dropped. Events
produced while a 'puter is `STOPPED` are not enqueued and are not replayed when it resumes;
events already in its queue remain there and continue to use the owner's allowance until they are
processed or lost.

### State Durability

The execution id, owner, current state, and sequence counters are durable across
server restarts and chunk reloads while the Cobbleputer has not crashed or been broken. When a
'puter crashes, its old execution id and sequence counters are discarded, but its owner
and most recently stored state remain durable so that its owner can restart it. That restart gets a
fresh execution id and sequence counters.

When a chunk falls below Minecraft's `BLOCK_TICKING` status, its running 'puter immediately stops
counting toward the execution limit. On the next normal server tick, the Mod stops it and records that
it should be resumed when that chunk becomes block-ticking again. The Mod resumes it only when its owner
has room under the running execution limit; a successful automatic resume delivers the usual `Resumed`
event to the Program. If there is no capacity, it remains stopped and its owner may later resume it
normally. The chunk can remain loaded in memory or be sent to clients outside that status without changing
this behavior.

Pending events and received effects are held only by the Mod while the Cobbleputer is active;
they are best-effort and can be lost on server restart or chunk unload. A queued event can be
dropped when its owner's shared in-memory allowance is full or when the Cobbleputer becomes
inactive. An event produced while a Cobbleputer is stopped is not enqueued, rather than being
dropped. Similarly, an effect is considered processed once the Mod attempts to apply it, but
an attempt can be missed or repeated around failures. When a 'puter is reset or a new Program
is loaded, its in-memory event queue is cleared.

### Limits

The Mod imposes limits on Cobbleputer executions to ensure fairness across the online player population
and prevent one player's executions from monopolizing server resources or degrading
server performance in ways that have a negative impact on gameplay, server stability, etc.

All limits are "soft" limits configurable by the server admin.

#### Running Executions Per Player

Each player is limited to an admin-configurable maximum number of running executions.

The Browser UI's Limits tab shows a player's current count of loaded, running executions against this cap. The value
is an operational view of the same accounting used for enforcement; it is near-real-time rather than a guarantee that
a later start or resume will still have capacity.

When attempting to start a new execution or resume a paused execution that would put the player
over the limit, the player instead receives an error message via chat.

When a chunk containing a Cobbleputer that was running falls below `BLOCK_TICKING`, that execution
releases its slot. When the chunk becomes block-ticking again, the Mod considers its requested resume
against the owner's current limit before it runs, so the returning Cobbleputer never temporarily exceeds
that limit. If the limit is full, the Cobbleputer remains stopped and the player receives an error message
via chat.

#### Queued Events Per Player

Each player has an administrator-configurable limit on the total number of in-memory events
queued across their active Cobbleputers. It defaults to 200. Stopped Cobbleputers retain their
previously queued events and continue to count against this limit, but newly produced events for
them are rejected immediately. Acknowledged events, crashes and resets, and a Cobbleputer becoming
inactive free its share of the allowance.

#### Total Execution State Per Player

Each player is limited to an admin-configurable total number of bytes across every durable Cobbleputer execution
state they own. The total counts only state byte arrays, including stopped, crashed, unloaded, and running
Cobbleputers. When an event handler's final state would exceed the remaining total, that returned state is discarded
and the Cobbleputer crashes while retaining its prior state for `/cobbleware resume` recovery. The Player receives a
limit-exceeded log entry and the usual crash transition. The Browser Limits tab shows the total state-byte usage and
cap with live updates from execution statistics.

#### Chat Effect Rate Limit Per Player

Chat provides convenient and low-effort interactivity, but is also easily abused. Cobbleputer
programs must not be able to flood chat in a way that makes it unusable for players.

Chat effects are rate-limited via a two-level token bucket approach, with a "slow" chat bucket feeding
into a "burst" chat bucket.
The slow bucket holds up to 100 tokens, replenishing at a rate of 1 token every 60 seconds.
The burst bucket holds up to 10 tokens, drawing tokens from the long-term bucket at a rate
of one token every five second.

Each chat effect emitted by an execution draws a token from the player's burst chat bucket.
If it contains no tokens, the effect is dropped.

The result of this approach is that a program can send brief bursts of messages, but is quickly
limited to one message per 5 seconds. A program that emits chat messages at that rate continuously
will, within ~10 minutes, be further restricted to one message per minute.

## Execution Pools

A server assigns running Cobbleputers to one of two execution pools:

* Executions in the guaranteed pool are synced every game tick. Every execution in the guaranteed
  pool has a dedicated User Worker slot in the runtime.
* Eligible players outside the guaranteed pool wait in a FIFO best-effort queue. They enter
  the fixed-size best-effort pool for five-second slices and are round-robin rotated through its
  User Worker slots, so queued programs make progress periodically.

Executions are assigned to the pools according to the _execution priority_ of their owning player.

### Execution Priority

Every player has an _online execution priority_ and an _offline execution priority_. Valid priority values
are 1, 2, and 3. The default online priority is 2 and the default offline priority is 3. An administrator
can persistently set either value with `/cobbleware setpriority <player> <online|offline> <priority>`.
Any player can view their own two priorities with `/cobbleware priority`; an administrator can view another
player's priorities with `/cobbleware priority <player>`.

## Wireless Observer

Wireless Observer blocks allow a Cobbleputer to detect block state changes and control
Redstone-enabled blocks and Redstone circuits. A placed Wireless Observer block
has a UUID in its block entity that uniquely identifies it. That UUID is not preserved
when the block is broken.
Wireless Observers come in 16 colors, one for each of the 16 dye variants.
A Wireless Observer is initially unpaired when placed, and breaking a paired Wireless Observer unpairs it.
Breaking a Cobbleputer eventually unpairs its Wireless Observers: loaded Observers are unpaired immediately,
while unloaded Observers unpair once their pairing can next be checked. Unpaired Observers emit no Redstone power.
A placed Wireless Observer may be paired to a specific 'puter by
using a paired Cobblemeter on the placed Wireless Observer.
When a Wireless Observer is paired, the associated Cobbleputer receives a
`WirelessObserverPaired` event. If the Observer was previously paired with a different
Cobbleputer, that Cobbleputer receives a `WirelessObserverUnpaired` event.
A `WirelessObserverUnpaired` event is also emitted when a Wireless Observer block
is broken.

A placed Wireless Observer has an orientation: it points along an axis. Orientation is
determined during placement as with pistons and observers: The Wireless Observer faces
the block it was placed against.

When the block in front of a Wireless Observer changes type or block state, the paired
Cobbleputer receives a `BlockStateChange` event. Changes occurring during one server tick
are combined into one event containing the state before the first change and the final
state. Delivery is best-effort: changes can be dropped while the paired Cobbleputer is
unloaded or its owner's queued-event allowance is full; changes produced while it is stopped are not enqueued.
Neither case is replayed later. Observers
continue tracking their facing block while unpaired so pairing does not produce stale
changes.

Paired Wireless Observers are also controllable redstone emitters. In their initially
placed state they emit no redstone signal. Once paired, a Cobbleputer can emit
a `SetRedstonePower` effect to set the redstone signal power level of paired
Wireless Observers to any level from 0 (off, the default) to 15.
With no target selector, the effect applies to every loaded Wireless Observer
paired with the emitting Cobbleputer. It can instead target observers of a
specific color or one specific observer; when both selectors are present, an
observer must match both. Observers paired to another Cobbleputer, unloaded
observers, and invalid color selectors are not affected.
Pairing a Wireless Observer to a different Cobbleputer resets its power level to 0.
Wireless Observers emit weak redstone signal out their backs, in the direction opposite their facing.

## Cobblemeter

A Cobblemeter is an item that can be used by the player to pair or unpair Wireless Observers.

A Cobblemeter is initially unpaired.
A Cobblemeter (whether already paired or not) can be paired with a Cobbleputer by using it on the Cobbleputer.

For running/stopped/crashed cobbleputers, ownership of the Cobbleputer and permission level determine
who can pair a cobblemeter:
* users with permission level 2 or greater can pair with any cobbleputer
* less privileged users can only pair with their own cobbleputers

Using an unpaired Cobblemeter on an unpaired Wireless Observer has no effect.

Using an unpaired Cobblemeter on a paired Wireless Observer behaves as
if using an unpaired Cobblemeter on the Cobbleputer to which the observer is paired.

Using a paired Cobblemeter on an unpaired Wireless Observer pairs that
observer with the Cobblemeter's associated Cobbleputer.

Using a paired Cobblemeter on a Wireless Observer paired with the same Cobbleputer
unpairs the Wireless Observer. Using a paired Cobblemeter on a Wireless Observer
paired with a different Cobbleputer has no effect.

Any player can use a paired cobblemeter to pair an unpaired Wireless Observer.
For paired Wireless Observers, however, only the player owning the Cobbleputer
to which it is paired, or a player with permission level 2 or higher, may unpair.

While holding a paired Cobblemeter the player can "see"
the wireless transmission paths from the paired 'puter to its paired Wireless Observers.

## Events

Cobbleputer programs are fundamentally event-driven state machines.
The sub-sections below describe the types of events that can be delivered
to running Cobbleputers.

Events are only enqueued for _running_ Cobbleputers (i.e. Cobbleputers
in the `RUNNING` state, which are Cobbleputers with associated executions).

### Chat Message

When a chat message is sent by a player within 16 units of the Cobbleputer or a paired Wireless Observer,
a `ChatMessage` event is queued for the associated Cobbleputer. At most one event per Cobbleputer
is queued per chat message; in other words, multiple observers paired to the same 'puter
and in range of the chat message do not produce separate events.

### Block State Changes

When the [block state](https://minecraft.wiki/w/Block_states)
of the block directly observed by a paired Wireless Observer changes,
a `BlockStateChange` event is queued for the associated Cobbleputer.
The event identifies and locates the triggering observer and the changed block,
and provides both the old and new block types and states.

### Cobbleputer Lifecycle Events

When an execution is started, it receives a `Started` event with the game tick
on which it was started.

For various reasons, an execution may become _inactive_, which means the execution
state is preserved but events are not being dispatched. Reasons include:

* The owning player explicitly stopped the execution.
* The chunk containing the host Cobbleputer was unloaded.
* The execution crashed.
* There are insufficient server resources available for the execution to make progress.

When an inactive execution becomes active again it receives a `Resumed` message
delivering the game ticks on which the execution became inactive and then active again.

### Wireless Observer Pairing

When a Wireless Observer is paired to or unpaired from a Cobbleputer with a running
execution, the execution receives a `WirelessObserverPaired` or `WirelessObserverUnpaired` event.