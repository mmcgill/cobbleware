# Gameplay

The mod introduces:

* Cobbleware _programs_, immutable ESZip-bundled JS/TS programs uploaded and owned by Players.
* A Cobbleputer ('puter) block for executing programs.
* A Wireless Redstone block that acts as both a sensor and a switchable Redstone signal source.
* A Cobblemeter item that can be used to pair Wireless Redstone blocks and troubleshoot Cobbleputers.

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
'puter with `/cobbleware resume`; right-clicking the block does not change its
lifecycle state. If an unhandled exception bubbles up out of a Program's event
handler function, the 'puter enters the `CRASHED` state.

Each 'puter has a state value that is provided as input to each event handler, and can
be arbitrarily modified by that event handler. The state value has a maximum size in bytes
given as server configuration. If an event handler returns a state value larger than this size,
the 'puter crashes and the returned state value is discarded.

Each 'puter has an associated in-memory event queue with a current maximum length of 10.
If events are produced faster than the 'puter can process them, the queue will eventually fill.
When the event queue is full, new events are dropped.

### State Durability

The execution id, execution owner, current state, and sequence counters are durable across
server restarts and chunk reloads while the Cobbleputer has not crashed or been broken.
Pending events and received effects are held only by the Mod while the Cobbleputer is active;
they are best-effort and can be lost on server restart or chunk unload. A queued event can be
dropped when the in-memory queue is full (currently 10 events) or when the Cobbleputer becomes
inactive. Similarly, an effect is considered processed once the Mod attempts to apply it, but
an attempt can be missed or repeated around failures. When a 'puter is reset or a new Program
is loaded, its in-memory event queue is cleared.

## Wireless Redstone

Wireless Redstone blocks allow a Cobbleputer to control Redstone-enabled blocks and Redstone circuits.
Wireless Redstone blocks come in 16 colors, one for each of the 16 dye variants.
A Wireless Redstone block is initially unpaired when placed, and breaking a paired Wireless Redstone block
unpairs it.
A placed Wireless Redstone block may be paired to a specific 'puter by
using a paired Cobblemeter on the placed Wireless Redstone block.

## Cobblemeter

A Cobblemeter is an item that can be used by the player to pair Wireless Redstone blocks
and troubleshoot Cobbleputers.
A Cobblemeter is initially unpaired.
A Cobblemeter (whether already paired or not) can be paired with a Cobbleputer by using it on the Cobbleputer.

While holding a paired Cobblemeter the player can "see"
the wireless transmission paths from the paired 'puter to its paired Wireless Redstone blocks.
