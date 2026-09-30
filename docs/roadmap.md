# Tentative Implementation Roadmap

Here's a rough sketch of the current plan to getting something working end-to-end:

* [x] Get Mod authentication and the `/modapi/v1/authentication` endpoint implemented.
* [x] Get the `/playerapi/v1/authenticate` endpoint implemented.
* [x] Implement basic program management use cases.
* [x] Sketch out a minimal set of starting events and effects
* [x] Get a "hello world" program written
* [x] Initial program execution in the Mod API
* [x] Basic Mod scaffold
* [x] First interactions
* [x] Cobblemeter
  * [x] Pairing
* [x] Wireless Observer
  * [x] Pairing
  * [x] Color
  * [x] Cobblemeter path visualization
  * [x] Block state change
  * [x] Set Redstone level
* [x] In-memory Mod instrumentation foundation
  * [x] Configurable sliding-window execution, event, effect, and state metrics
* [x] Per-Player stats in the Browser UI
  * [x] Memory utilization
  * [x] Aggregate event/effect rates
* [ ] Limits
  * [x] Per-player active executions
  * [ ] Per-player effects per tick
  * [x] Per-player memory utilization (via runtime)
  * [x] Per-player total execution state size
  * [x] Per-player queued events
* [ ] Drop events for Players not online (configurable)
* [ ] Cobbleputer art assets
* [ ] Wireless Observer art assets
* [ ] Bundling the runtime in the Mod
  * [ ] Bundling: getting the runtime into the Mod Jar
  * [ ] Lazy unpacking at mod execution time as necessary
  * [ ] Putting the Mod in charge of the Runtime process 
  * [ ] Preserving a good Development workflow
