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
* [ ] Per-Player stats in the Browser UI
  * [ ] Memory utilization
  * [ ] Aggregate event/effect rates
* [ ] Drop events for Players not online (configurable)
* [ ] Cobbleputer art assets
* [ ] Wireless Observer art assets
