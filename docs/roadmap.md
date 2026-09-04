# Tentative Implementation Roadmap

Here's a rough sketch of the current plan to getting something working end-to-end:

* [x] Get Mod authentication and the `/modapi/v1/authentication` endpoint implemented.
* [x] Get the `/playerapi/v1/authenticate` endpoint implemented.
* [x] Implement basic program management use cases.
* [x] Sketch out a minimal set of starting events and effects
* [x] Get a "hello world" program written
* Initial program execution in the Mod API
  * `/modapi/v1/executions` handles Runtime <-> Mod coordination in both directions
  * We can test this stuff as-is without even doing Minecraft things
* Basic Mod scaffold
  * Minecraft-side Cobbleputer basics, no UI
* First interactions
  * slash command to list initial states
  * slash command to start an execution
* Wireless Redstone
  * Console command to pair all Wireless Redstone itemstacks in inventory
    with a specific 'puter
  * Emitting events
