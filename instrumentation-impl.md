# Mod instrumentation implementation plan

## Scope and decisions

This increment adds only in-memory recording in the Fabric Mod.  It does not
add `/cobbleware stats` or `/cobbleware top`, a Main Worker endpoint, browser
UI changes, persistence, export, or logging/reporting of the collected data.

The configured retention window is measured in server ticks.  Its default is
600 ticks (30 seconds at Minecraft's normal 20 ticks/second).  Event metrics
distinguish accepted events from dropped events; effect metrics distinguish
application attempts from attempts that throw.  Execution-sync latency is the
full wall-clock interval from dispatching `MainWorkerClient.syncExecutions` to
the returned future's completion callback, rather than to the later server-tick
processing of its result.

## Metric contract

Create an `instrumentation` package whose collector owns a fixed-size,
tick-indexed ring buffer and exposes immutable snapshots for future command/UI
consumers.  Do not expose its mutable buckets or internal maps.

Each bucket represents one server tick and retains the following data:

* Global execution-sync measurements: completion count, sum and maximum
  latency in nanoseconds, plus completion counts grouped by
  `ExecutionSyncStatus`.  A latency is recorded for every dispatched sync
  whose future completes, including unsuccessful completions; status
  makes unavailable/configuration failures distinguishable from successes.
* Per-player and per-Cobbleputer event counts, split into `accepted` and
  `dropped`, and further keyed by the protobuf event oneof case.  The
  Cobbleputer key is its dimension and block position (rather than a block
  entity instance or execution ID), so a chunk reload or a restarted execution
  remains the same physical Cobbleputer during the retained window.
* Per-player and per-Cobbleputer effect counts, split into `attempted` and
  `failed`, keyed by effect oneof case.  Each attempt also contributes an
  effect-processing duration (count, total nanoseconds, maximum nanoseconds).
  An effect is failed only when `applyEffect` throws; no-op/best-effort effects
  that complete normally remain attempted-but-not-failed, matching the Mod's
  existing acknowledgement semantics.
* A per-player, per-lifecycle-state Cobbleputer gauge captured once per driver
  tick.  It counts loaded, active Cobbleputers with a complete execution record
  and their current visible `RUNNING` or `STOPPED` state.  Uninitialized and
  crashed blocks have no current owner after the existing crash cleanup and are
  therefore not attributed to a player.

The collector advances once for every `ExecutionDriver` server tick.  Reusing
the slot for a new tick discards that tick's old data.  Snapshot assembly
merges the retained buckets, producing window-wide counters/timing summaries
and the latest state-gauge sample.  This preserves the requested sliding
window without retaining an unbounded all-time map.  The snapshot should state
the configured window length and the actual retained tick range/sample count,
so a future presenter can distinguish a warm-up period from a full window.

Use a player UUID as the player key, and value objects for player/Cobbleputer
and player/state keys.  Store plain identifiers/coordinates, not Minecraft
world, block-entity, or player object references.  This keeps all collected
data ephemeral and safe across chunk unloads.

## Implementation steps

1. Extend `CobblewareConfig` and `CobblewareConfigLoader` with an optional JSON
   integer such as `instrumentationWindowTicks` and the `600` default. Missing
   values in existing configuration files use that default; zero, negative,
   non-integral, and out-of-range supplied values follow the existing
   configuration-error path. Update `CobblewareConfigLoaderTest`'s expected
   default JSON and add acceptance, migration, and rejection coverage.
   Document this setting in the Mod configuration documentation and the Java
   Mod README.

2. Add the instrumentation model and collector under
   `java/cobbleware/src/main/java/io/github/mmcgill/cobbleware/instrumentation/`.
   Keep the API deliberately small: advance the clock, record sync completion,
   record an event outcome, record an effect attempt with duration/outcome,
   record a state-gauge snapshot, and retrieve an immutable aggregate
   snapshot.  Make duration handling injectable or accept durations as values;
   leave `System.nanoTime()` calls at `ExecutionDriver`'s measurement boundary
   so collector tests remain deterministic.

3. Construct one collector in `CobblewareMod` from the loaded configuration,
   inject it into `ExecutionDriver`, and provide a read-only accessor through
   the driver for the future command/UI integration.  Do not register new
   Brigadier commands in this increment.  Preserve the existing initialization
   and chunk lifecycle behavior.

4. Instrument `ExecutionDriver`'s tick lifecycle.  At the start of `tick`,
   advance the collector to the current server tick.  Extend `Flight` with its
   monotonic dispatch timestamp.  Set that timestamp immediately before
   calling `syncExecutions`, and attach a `whenComplete` callback immediately
   to the returned future.  The callback must capture
   `System.nanoTime() - dispatchedAt` when that future completes, not when a
   later `END_SERVER_TICK` notices `isDone()`.  It may safely record a
   thread-safe completion measurement immediately, or save the already
   calculated elapsed duration on the flight for server-thread aggregation;
   either option preserves the true response-arrival duration.  Include the
   returned sync status (or an exceptional-completion outcome) with that
   measurement before the response is applied or rejected.  Leave the single
   in-flight-request guarantee and all existing response handling unchanged.

5. Instrument event admission centrally in `enqueueActiveEvent`.  Derive the
   owner UUID and physical Cobbleputer key from the target block entity, then
   record exactly one outcome for every attempted admission with an execution
   record: accepted after `addLast` succeeds; dropped for a non-running or
   incomplete execution, a full queue, or a sequence-number/other enqueue
   exception.  Also handle `enqueueEvent` calls for an inactive but otherwise
   identifiable Cobbleputer as dropped.  Determine the event type from
   `Event.getEventCase()`.  This covers lifecycle, chat, and wireless-observer
   delivery through their existing common path without altering their
   best-effort behavior.

6. Instrument `applyEffects`.  For every returned effect, obtain the owning
   player and physical Cobbleputer key before attempting application.  Measure
   each `applyEffect` call with `System.nanoTime()`, record an attempt and its
   elapsed time in a `finally`-style path, and mark it failed when the existing
   `RuntimeException` catch executes.  Continue acknowledging every effect in
   its existing sequence order regardless of the recorded outcome; recording
   must not change effect semantics or error handling.

7. At a consistent point near the end of every driver tick—after a completed
   response can update lifecycle state—scan `activeExecutions` and replace the
   collector's current-tick state-gauge sample.  Include only valid owned
   executions and translate the visible `CobbleputerState` to the recorded
   lifecycle state.  This ensures a crash/result transition is represented in
   the same tick it is applied, while unloaded entities naturally disappear
   from the next sample.

8. Replace the draft `## Instrumentation` section in `docs/mod.md` with the
   finalized recording contract: the four metric families, 600-tick default
   setting, sliding-window/in-memory lifetime, and explicit statement that
   `stats` and `top` commands are future work.  Keep `docs/roadmap.md` aligned
   by describing this as the instrumentation foundation, not the already
   delivered browser UI feature.

## Tests and verification

* Unit-test the ring buffer with a small window: aggregation across retained
  ticks, eviction when a slot is reused, warm-up metadata, latest-gauge
  behavior, UUID/position grouping, and immutable snapshots.
* Add deterministic `ExecutionDriver` tests using a controllable clock and
  completed/pending `CompletableFuture`s (or extract minimal seams for those
  dependencies).  Verify the completion callback records dispatch-to-future-
  completion latency once—even if the next server tick is delayed—including an
  unsuccessful sync, and that no second request starts while one remains in
  flight.
* Cover accepted and dropped events for a full queue, non-running/inactive
  target, and enqueue failure.  Assert owner, physical Cobbleputer, and event
  type attribution.
* Cover a normally applied effect and an `applyEffect` exception; assert
  attempted/failed counters, non-negative timing summaries, type attribution,
  and unchanged contiguous acknowledgement behavior.
* Cover state-gauge snapshots across running/stopped transitions, chunk
  deactivation, and crash cleanup.
* Run `java/cobbleware/gradlew.bat test`, then inspect `git diff --check`.
