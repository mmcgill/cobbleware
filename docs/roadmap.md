# Tentative Implementation Roadmap

Here's a rough sketch of the current plan to getting something working end-to-end:

* Get Mod authentication and the `/modapi/v1/authentication` endpoint implemented.
* Get the `/playerapi/v1/authenticate` endpoint implemented.
* Implement basic program management use cases.
* Sketch out a minimal set of starting events and effects
* Get a "hello world" program written
  * startup event to initialize state
  * door event
  * host w/ local http server
* Initial program execution in the Mod API
  * `/modapi/v1/dispatch` manages per-player queues, then returns
  * Get an event loop in place that manages per-player isolates, feeds them events, and puts them in per-puter result buffers
  * `/poll` pulls round-robin from result buffers for fairness
  * We can test this stuff as-is without even doing Minecraft things
* Basic Mod scaffold
  * Minecraft-side Cobbleputer basics, no UI
* First interactions
  * console command to download code to 'puter by its ID
* Per-puter queue
  * CLI command to enqueue bogus door event for a 'puter
  * Per-tick code to collect up all enqueued events and invoke
  * Per-tick code to poll results (no effects yet) and drop events
    from queue
* Wireless Redstone
  * Console command to pair all Wireless Redstone itemstacks in inventory
    with a specific 'puter
  * Emitting events
