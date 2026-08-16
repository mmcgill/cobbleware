# Tentative Implementation Roadmap

Here's a rough sketch of the current plan to getting something working end-to-end:

* Sketch out a minimal set of starting events and effects
* Get a "hello world" mainframe program written
  * startup event to initialize state
  * door event
  * host w/ local http server
* Initial edge-runtime with customized main:
  * `/installFunction` impl downloads code, no type checking
  * `/invokeBatch` manages per-mainframe queues, then returns
  * Get an event loop in place that manages per-mainframe isolates, feeds them events, and puts them in per-mainframe result buffers
  * `/poll` pulls round-robin from result buffers for fairness
  * We can test this stuff as-is without even doing Minecraft things
* Basic Mod scaffold
  * Minecraft-side Mainframe basics, no UI
* First interactions
  * console command to download code to Mainframe by its ID
* Per-Mainframe queue
  * CLI command to enqueue bogus door event for a Mainframe
  * Per-tick code to collect up all enqueued events and invoke
  * Per-tick code to poll results (no effects yet) and drop events
    from queue
* Microcontroller
  * Console command to pair all Microcontroller itemstacks in inventory
    with a specific mainframe
  * Attaching Microcontroller to doors (?!?)
  * Emitting events
