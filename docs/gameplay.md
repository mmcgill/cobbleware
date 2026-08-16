# Gameplay

The mod introduces:

* A Mainframe block for storing and executing functions.
* A Microcontroller item that can be attached to supported items/blocks to make them wirelessly controllable from a Mainframe.
* A Debug tool that can be used to view Mainframe outputs.

## Mainframes

A Mainframe block stores and executes functions written in Typescript. Mainframe functions are event handlers.
They are invoked by the Mainframe as a result of events that happen in the Minecraft world, and they can return
Effects to be processed by the Mainframe as output.

Each Mainframe block has a unique, persistent identity. When the block is broken, the resulting item
retains all Mainframe state. Distinct Mainframe items do not stack. Each Mainframe has a name.
Its name is initially auto-generated, but may be changed via Nametag in the usual way.

A player programs a Mainframe by:
* using the Mainframe block to open a UI element with a URL text input
* entering a URL and pressing "Download"

The mod then attempts to download the current contents of the URL and typecheck them.
If the response body is larger than a server-configured maximum, an error is sent to
the player in chat
and the Mainframe state is unaffected. If the contents do not type check, the first
type error is sent to the player in chat and the Mainframe state is unaffected.
If the contents typecheck, the Mainframe state is reset and the newly installed
code begins to respond to events.

### Execution Model

A Mainframe is single-threaded, and executes events sequentially in the order they are produced.

A Mainframe is either `UNINITIALIZED`, `RUNNING`, `STOPPED` or `CRASHED`. A newly placed Mainframe
is in the `UNINITIALIZED` state. A User can load code as described above for a Mainframe in any
state. On successful load, the Mainframe's state set to `RUNNING`. The user may stop a running
Mainframe via the use menu. If a function invocation throws an exception, the Mainframe enters
the `CRASHED` state. The user can see the error in the use menu, and restart the Mainframe.

A Mainframe has a maximum memory limit whose value is configurable by the server admin.
If a function invocation exceeds the memory limit the function fails with a runtime error.

Each Mainframe has a JSON-serializable state value that is provided as input to each event handler, and can
be arbitrarily modified by that event handler. The state value has a maximum size in bytes
given as server configuration. If an event handler returns a state value larger than this size,
the function fails with a runtime value and the returned state value is discarded.

A Mainframe has a user-controllable setting (via the Use menu) for whether or not the Mainframe
crashes when a runtime error is encountered. When true, any runtime error immediately transitions
the Mainframe into the `CRASHED` state. No further functions are executed until the user restarts
the Mainframe.

Each Mainframe has an associated event queue whose length is determined by server configuration.
If events are produced faster than the Mainframe can process them, the queue will eventually fill.
When the event queue is full, new events are dropped.

### State Durability

Once an event makes it into the Mainframe's queue, it is durable across Server restarts.
Mainframe Queue state is persisted as part of the game state. A queued event will
be processed. If a function throws a runtime error while processing an event, that
event is still considered processed. For every processed event that successfully produces
a result, the effects on both Mainframe state and the game world itself are durable and
will be processed. An effect on the game world is considered processed if the Mod
attempts to apply the effect; some effects may be not be _applicable_ by the time
they are processed.

## Microcontrollers

Microcontrollers are items that act as wireless I/O devices for a Mainframe. A Microcontroller
is initially unpaired. Unpaired Microcontrollers are unusable and inert.
A Microcontroller may be paired to a specific Mainframe.
Unpaired Microcontrollers stack with one another. Microcontrollers paired to the same
Mainframe stack with one another. Microcontrollers paired to different mainframes do not
stack with one another or unpaired Microcontrollers.

Use a paired Microcontroller on a block of a supported type to attach it. An attached Microcontroller can be
removed by using shears on the block to which it is attached.

Not yet specified:

* how to craft/obtain
* how to tether to a Mainframe
* how to attach a Microcontroller to an entity

## Debug Tool

A Debug tool can be used by the player to troubleshoot a Mainframe. A Debug tool is initially
unpaired. An unpaired Debug tool can be paired with a Mainframe by using it on that Mainframe.

When the player holds a paired Debug tool and its paired Mainframe is being simulated by the
Server (regardless of distance!), console output from that Mainframe's function executions is
printed to the player's chat.