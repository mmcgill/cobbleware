# Events and Effects

Running Cobbleputers transform sequences of Events into sequences of Effects.

The usability, maintainability and performance of the mod depend on
the extent to which mod maintainers can keep a variety of Event/Effect
descriptions in sync:

* Human/Agent-facing documentation of Event/Effect types and fields, and their intended interpretations
* Wire representations for IPC between the Mod and the Runtime
* Type-level descriptions in Java and Typescript
* Persistent representations stored in game save state

## FlatBuffers?

We should do an initial test to see how much FlatBuffers buys us:

* Define a complicated composite data structure.
* Set up a Java driver that randomly generates data and pumps it through
  an edge function API as JSON.
* Set up an edge function that reads a particular field and returns it.
* Benchmark. What's the max throughput we get single-threaded?
* Do the same, but with FlatBuffers.