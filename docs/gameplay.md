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
A player can run their active program on a placed 'puter block via the `/cobbleware exec <start-state>`
command. An executing 'puter block stores its own runtime state, which persists across
chunk reloads and server restarts.

When an executing 'puter block is broken, its associated execution is terminated and its
execution state is destroyed. 'puter block items are from one another.

The Mod does not itself provide any notion of player ownership
of 'puter blocks. Once a 'puter is placed, any player can interact with it.
The 'puter _state_, however, remains associated with the player that loaded the
associated program. Only that player can pause or resume the execution with
`/cobbleware pause` or `/cobbleware resume`. Those commands select the closest
'puter within 3m, as `/cobbleware exec` does. Loading a new program creates a
new execution state that is associated with the player doing the loading, even
if the previous execution state was bound to a different player. Resetting a
'puter _preserves_ execution ownership.

### Execution Model

A Cobbleputer is single-threaded, and executes events sequentially in the order they are produced.

A 'puter is either `UNINITIALIZED`, `RUNNING`, `STOPPED` or `CRASHED`. A newly placed 'puter
is in the `UNINITIALIZED` state. A User can load a program as described above for a 'puter in any
state. On successful load, the 'puter state is set to `RUNNING`. Its associated
player may pause a running 'puter with `/cobbleware pause` and resume a stopped
or crashed 'puter with `/cobbleware resume`; right-clicking the block does not
change its lifecycle state. If an unhandled exception bubbles up out of a
Program's event handler function, the 'puter enters the `CRASHED` state. Resuming
a crashed 'puter starts a new execution using its most recently stored state.

Each 'puter has a state value that is provided as input to each event handler, and can
be arbitrarily modified by that event handler. Its maximum returned-state size is dynamically
derived from its owner's remaining total execution-state capacity. If an event handler returns a
state value larger than that allowance, the 'puter crashes and the returned state value is discarded.

Each 'puter has an associated in-memory event queue with a current maximum length of 10.
If events are produced faster than the 'puter can process them, the queue will eventually fill.
When the event queue is full, new events are dropped.
Events produced while a 'puter is `STOPPED` are not enqueued and are not replayed when it resumes;
events already in its queue remain there until the 'puter resumes.

### State Durability

The execution id, execution owner, current state, and sequence counters are durable across
server restarts and chunk reloads while the Cobbleputer has not crashed or been broken. When a
'puter crashes, its old execution id and sequence counters are discarded, but its execution owner
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
dropped when the in-memory queue is full (currently 10 events) or when the Cobbleputer becomes
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

#### Total Execution State Per Player

Each player is limited to an admin-configurable total number of bytes across every durable Cobbleputer execution
state they own. The total counts only state byte arrays, including stopped, crashed, unloaded, and running
Cobbleputers. When an event handler's final state would exceed the remaining total, that returned state is discarded
and the Cobbleputer crashes while retaining its prior state for `/cobbleware resume` recovery. The Player receives a
limit-exceeded log entry and the usual crash transition. The Browser Limits tab shows the total state-byte usage and
cap with live updates from execution statistics.

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
unloaded or has a full event queue; changes produced while it is stopped are not enqueued.
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
Wireless Observers emit redstone signal in the direction they're facing.

## Cobblemeter

A Cobblemeter is an item that can be used by the player to pair Wireless Observers
and troubleshoot Cobbleputers.
A Cobblemeter is initially unpaired.
A Cobblemeter (whether already paired or not) can be paired with a Cobbleputer by using it on the Cobbleputer.

While holding a paired Cobblemeter the player can "see"
the wireless transmission paths from the paired 'puter to its paired Wireless Observers.
