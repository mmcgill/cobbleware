# Cobbleware Examples

A collection of Cobbleware programming examples in a single bundle.

From this directory:

```sh
deno task test
deno task check
deno task bundle
deno task serve
deno task integrate
```

The `bundle` task tree-shakes the program with Deno's esbuild-powered bundler
before writing `examples.eszip`; it intentionally does not minify the
resulting JavaScript. Set `EDGE_RUNTIME_BIN` to use a different Edge Runtime
binary. The serve task packages the program and serves the archive at
`http://127.0.0.1:8000/examples.eszip`.

The `integrate` task is not included in `deno task test`. It bundles the current
program, then exercises a Main Worker at `http://localhost:9000` using the Mod API
key `secret`.

## Conway's Game of Life

This example illustrates how to implement a moderately complex multi-stage program
that uses execution state to avoid dropping effects.

Its initial state is named `"conway"`.

The program uses three colors of wireless observer:

- Black observers serve as the pixels in the output display.
- Green observers control whether the program is in 'init' or 'simulate' mode.
- Blue observers are used as a clock signal to advance the simulation.

The chat commands `sim`, `init`, and `step` respectively enter simulation mode,
return to init mode, and advance the simulation. The program reports each mode
change in chat. Pairing a black observer also reports the paired-observer count
and the current screen size.

In 'simulate' mode, the program's execution state stores the board size,
the set of live cells on the board, and a queue of cell changes that need to be
applied to be sent to the "display" before the next simulation step can be taken.

The program's intended use is as follows:

- When program execution starts it is in 'init' mode. During this mode, the program records
  paired black observers (both id and position) in execution state and reports the total count
  and current screen size in chat.
- When the program in 'init' mode and receives a block state change from a green observer indicating
  that its observed block is powered, the program transitions to 'simulate' mode. At this transition,
  the program initializes the board by:
  - identifying the plane (xy, xz, yz) that contains the most black observers
  - using the min/max coordinates of black observers in that plane to pick board extents,
    capped to 64x64
  - assigning board i,j locations to observer ids
  - clearing power to all paired black observers
  - randomly assigning initial board state (with each cell having an 0.25 chance of being alive)
  - storing in program state the board size, state, and a queue of pixels to activate
- When the program is in 'simulate' mode:
  - receiving a block state change from a green observer indicating that its observed block is unpowered
    sends it back to 'init' mode while retaining the recorded paired black observers
  - receiving a block state change from a blue observer advances the simulation:
    - up to the max allowed effects are prepared for updating the "display"
    - if no queued display pixel changes remain in execution state, a new GOL board state is computed
      and the resulting display changes queued in internal state.
  - receiving the chat message `step` advances the simulation, and receiving `init` returns it to
    init mode. The `sim` chat message enters simulation mode while in init mode.
