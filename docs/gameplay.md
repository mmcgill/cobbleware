# Gameplay

The mod introduces:

* Cobbleware _programs_, immutable ESZip-bundled JS/TS programs uploaded and owned by Players.
* A Cobbleputer ('puter) block for executing programs.
* A Wireless Redstone block that acts as both a sensor and a switchable Redstone signal source.
* A Debug tool that can be used to view 'puter outputs.

## Programs

Programs are ESZip-bundled JS/TS programs uploaded by players externally via a browser-based UI.
Programs are not first-class from a gameplay standpoint. They have no explicit
in-game representation as blocks, items or entities.

## Cobbleputers

A Cobbleputer block executes Programs. Cobbleputer programs are event handlers.
They are invoked by the 'puter as a result of Events that happen in the Minecraft world, and they can return
Effects to be processed by the Mainframe as output.

Each 'puter block has a unique, persistent identity. When the block is broken, the resulting item
retains all 'puter state. Distinct 'puter items do not stack. Each 'puter has a name.
Its name is initially auto-generated, but may be changed via Nametag in the usual way.

A player loads a program on a placed `puter block by right-clicking and selecting one of their
programs in the in-game UI. Loading a new program causes it to begin executing immediately.
A 'puter can also be _reset_ in its UI, without reloading the current program.

The Mod does not itself provide any notion of player ownership
of 'puter blocks. Once a 'puter is placed, any player can interact with it.
The 'puter _state_, however, remains associated with the player that loaded the
associated program. Loading a new program creates a new execution state that is
associated with the player doing the loading, even if the previous execution
state was bound to a different player. Resetting a 'puter _preserves_ execution ownership.

### Execution Model

A Cobbleputer is single-threaded, and executes events sequentially in the order they are produced.

A 'puter is either `UNINITIALIZED`, `RUNNING`, `STOPPED` or `CRASHED`. A newly placed 'puter
is in the `UNINITIALIZED` state. A User can load a program as described above for a 'puter in any
state. On successful load, the 'puter state set to `RUNNING`. The user may stop a running
'puter via the use menu. If an unhandled exception bubbles up out of a Program's event handler function,
the 'puter enters the `CRASHED` state. The user can see the error in the use menu, and restart the 'puter.

Each 'puter has a JSON-serializable state value that is provided as input to each event handler, and can
be arbitrarily modified by that event handler. The state value has a maximum size in bytes
given as server configuration. If an event handler returns a state value larger than this size,
the function fails with a runtime value and the returned state value is discarded.

Each 'puter has an associated event queue whose length is determined by server configuration.
If events are produced faster than the 'puter can process them, the queue will eventually fill.
When the event queue is full, new events are dropped.

### State Durability

Once an event makes it into the puter's queue, it is durable across Server restarts.
'puter Queue state is persisted as part of the game state. A queued event will
be processed. If a function throws a runtime error while processing an event, that
event is still considered processed. For every processed event that successfully produces
a result, the effects on both 'puter state and the game world itself are durable and
will be processed. An effect on the game world is considered processed if the Mod
attempts to apply the effect; some effects may be not be _applicable_ by the time
they are processed. When a 'puter is reset or a new Program is loaded, its event queue is cleared.

## Wireless Redstone

Wireless Redstone blocks allow a Cobbleputer to control Redstone-enabled blocks and Redstone circuits.
Wireless Redstone blocks come in 16 colors, one for each of the 16 dye variants.
A Wireless Redstone block is initially unpaired when placed, and breaking a paired Wireless Redstone block
unpairs it.
A Wireless Redstone block may be paired to a specific 'puter by using the block on the 'puter,
or by using a paired Debug Tool on the placed Wireless Redstone block.
Unpaired Wireless Redstone stack with one another. Wireless Redstone paired to the same
'puter stack with one another. Wireless Redstone paired to different 'puters do not
stack with one another or unpaired Wireless Redstone blocks.

## Debug Tool

A Debug tool can be used by the player to troubleshoot a 'puter. A Debug tool is initially
unpaired. An unpaired Debug tool can be paired with a 'puter by using it on that Mainframe.

When the player holds a paired Debug tool and its paired 'puter is being simulated by the
Server (regardless of distance!), console output from that 'puter's function executions is
printed to the player's chat. Similarly, while holding a paired Debug Tool the player can "see"
the wireless transmission lines from the paired 'puter to its paired Wireless Redstone blocks.
