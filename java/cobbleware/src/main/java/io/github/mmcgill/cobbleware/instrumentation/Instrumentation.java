package io.github.mmcgill.cobbleware.instrumentation;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Retains bounded, in-memory execution instrumentation in a sliding window of server ticks.
 *
 * <p>The execution-sync completion callback may call this class off the server thread, so every
 * mutation and snapshot is synchronized. Values returned in {@link Snapshot} are immutable.
 */
public final class Instrumentation {
    private final TickBucket[] buckets;
    private long currentTick;

    public Instrumentation(int windowTicks) {
        if (windowTicks <= 0) {
            throw new IllegalArgumentException("instrumentation window must be positive");
        }
        buckets = new TickBucket[windowTicks];
        for (int index = 0; index < buckets.length; index++) {
            buckets[index] = new TickBucket();
        }
    }

    /** Advances the collector's server-tick clock. Repeated calls for one tick are harmless. */
    public synchronized void advanceTick(long tick) {
        if (tick < currentTick) {
            throw new IllegalArgumentException("instrumentation tick cannot move backwards");
        }
        currentTick = tick;
        currentBucket();
    }

    /** Records the elapsed time and final outcome of one execution-sync request. */
    public synchronized void recordSyncCompletion(long elapsedNanos, SyncOutcome outcome) {
        if (elapsedNanos < 0 || outcome == null) {
            throw new IllegalArgumentException("sync measurement must have a non-negative duration and outcome");
        }
        TickBucket bucket = currentBucket();
        bucket.syncDurations = bucket.syncDurations.add(elapsedNanos);
        bucket.syncOutcomes.merge(outcome, 1L, Instrumentation::saturatingAdd);
    }

    /** Records one accepted or dropped event admission. */
    public synchronized void recordEvent(PlayerCobbleputerKey target, Event.EventCase type, boolean accepted) {
        if (target == null || type == null) {
            throw new IllegalArgumentException("event measurement key and type must not be null");
        }
        TickBucket bucket = currentBucket();
        EventMetricKey key = new EventMetricKey(target, type);
        EventCounts current = bucket.events.getOrDefault(key, EventCounts.EMPTY);
        bucket.events.put(key, accepted ? current.incrementAccepted() : current.incrementDropped());
    }

    /** Records the number of events included for one Cobbleputer in one dispatched execution-sync request. */
    public synchronized void recordEventsPerSync(PlayerCobbleputerKey target, int eventCount) {
        if (target == null || eventCount < 0) {
            throw new IllegalArgumentException("event-sync measurement must have a target and non-negative event count");
        }
        TickBucket bucket = currentBucket();
        EventSyncSummary current = bucket.eventsPerSync.getOrDefault(target, EventSyncSummary.EMPTY);
        bucket.eventsPerSync.put(target, current.add(eventCount));
    }

    /** Records one effect application attempt and its duration. */
    public synchronized void recordEffect(
            PlayerCobbleputerKey target, Effect.EffectCase type, long elapsedNanos, boolean failed) {
        if (target == null || type == null || elapsedNanos < 0) {
            throw new IllegalArgumentException("effect measurement must have a key, type, and non-negative duration");
        }
        TickBucket bucket = currentBucket();
        EffectMetricKey key = new EffectMetricKey(target, type);
        EffectCounts current = bucket.effects.getOrDefault(key, EffectCounts.EMPTY);
        bucket.effects.put(key, current.add(elapsedNanos, failed));
    }

    /** Replaces the current tick's per-Cobbleputer lifecycle-state gauge. */
    public synchronized void recordCobbleputerStates(Map<PlayerCobbleputerKey, LifecycleState> states) {
        if (states == null || states.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null)) {
            throw new IllegalArgumentException("Cobbleputer state gauge must contain keyed lifecycle states");
        }
        currentBucket().states = Map.copyOf(states);
    }

    /** Returns window-wide counters and durations plus the most recent tick's state gauge. */
    public synchronized Snapshot snapshot() {
        TimingSummary syncDurations = TimingSummary.EMPTY;
        Map<SyncOutcome, Long> syncOutcomes = new HashMap<>();
        Map<EventMetricKey, EventCounts> events = new HashMap<>();
        Map<PlayerCobbleputerKey, EventSyncSummary> eventsPerSync = new HashMap<>();
        Map<EffectMetricKey, EffectCounts> effects = new HashMap<>();
        long firstTick = Long.MAX_VALUE;
        int retainedTickCount = 0;
        long windowStart = Math.max(0, currentTick - buckets.length + 1);

        for (TickBucket bucket : buckets) {
            if (bucket.tick < windowStart || bucket.tick > currentTick) {
                continue;
            }
            firstTick = Math.min(firstTick, bucket.tick);
            retainedTickCount += 1;
            syncDurations = syncDurations.add(bucket.syncDurations);
            mergeLongCounts(syncOutcomes, bucket.syncOutcomes);
            mergeEventCounts(events, bucket.events);
            mergeEventSyncSummaries(eventsPerSync, bucket.eventsPerSync);
            mergeEffectCounts(effects, bucket.effects);
        }

        TickBucket latest = buckets[(int) Math.floorMod(currentTick, buckets.length)];
        return new Snapshot(
                buckets.length,
                retainedTickCount == 0 ? 0 : firstTick,
                currentTick,
                retainedTickCount,
                syncDurations,
                Map.copyOf(syncOutcomes),
                Map.copyOf(events),
                Map.copyOf(eventsPerSync),
                Map.copyOf(effects),
                latest.tick == currentTick ? latest.states : Map.of());
    }

    private TickBucket currentBucket() {
        int index = (int) Math.floorMod(currentTick, buckets.length);
        TickBucket bucket = buckets[index];
        if (bucket.tick != currentTick) {
            bucket.reset(currentTick);
        }
        return bucket;
    }

    private static void mergeLongCounts(Map<SyncOutcome, Long> target, Map<SyncOutcome, Long> source) {
        source.forEach((key, value) -> target.merge(key, value, Instrumentation::saturatingAdd));
    }

    private static void mergeEventCounts(Map<EventMetricKey, EventCounts> target, Map<EventMetricKey, EventCounts> source) {
        source.forEach((key, value) -> target.merge(key, value, EventCounts::add));
    }

    private static void mergeEventSyncSummaries(
            Map<PlayerCobbleputerKey, EventSyncSummary> target, Map<PlayerCobbleputerKey, EventSyncSummary> source) {
        source.forEach((key, value) -> target.merge(key, value, EventSyncSummary::add));
    }

    private static void mergeEffectCounts(
            Map<EffectMetricKey, EffectCounts> target, Map<EffectMetricKey, EffectCounts> source) {
        source.forEach((key, value) -> target.merge(key, value, EffectCounts::add));
    }

    public enum SyncOutcome {
        SUCCESS,
        CONFIGURATION_ERROR,
        UNAUTHORIZED,
        UNAVAILABLE,
        EXCEPTION
    }

    public enum LifecycleState {
        RUNNING,
        STOPPED
    }

    /** Identifies a physical Cobbleputer without retaining a world or block-entity object. */
    public record CobbleputerKey(UUID id, String dimension, int x, int y, int z) {
        public CobbleputerKey {
            if (id == null || dimension == null || dimension.isEmpty()) {
                throw new IllegalArgumentException("Cobbleputer ID and dimension must not be empty");
            }
        }
    }

    public record PlayerCobbleputerKey(UUID playerId, String playerName, CobbleputerKey cobbleputer) {
        public PlayerCobbleputerKey {
            if (playerId == null || playerName == null || playerName.isEmpty() || cobbleputer == null) {
                throw new IllegalArgumentException("player identity and Cobbleputer must not be null or empty");
            }
        }
    }

    public record PlayerStateKey(UUID playerId, String playerName, LifecycleState state) {
        public PlayerStateKey {
            if (playerId == null || playerName == null || playerName.isEmpty() || state == null) {
                throw new IllegalArgumentException("player identity and lifecycle state must not be null or empty");
            }
        }
    }

    public record EventMetricKey(PlayerCobbleputerKey target, Event.EventCase type) {
        public EventMetricKey {
            if (target == null || type == null) {
                throw new IllegalArgumentException("event metric key fields must not be null");
            }
        }
    }

    public record EffectMetricKey(PlayerCobbleputerKey target, Effect.EffectCase type) {
        public EffectMetricKey {
            if (target == null || type == null) {
                throw new IllegalArgumentException("effect metric key fields must not be null");
            }
        }
    }

    public record TimingSummary(long count, long totalNanos, long maxNanos) {
        private static final TimingSummary EMPTY = new TimingSummary(0, 0, 0);

        public TimingSummary {
            if (count < 0 || totalNanos < 0 || maxNanos < 0) {
                throw new IllegalArgumentException("timing values must be non-negative");
            }
        }

        private TimingSummary add(long elapsedNanos) {
            return new TimingSummary(saturatingAdd(count, 1), saturatingAdd(totalNanos, elapsedNanos),
                    Math.max(maxNanos, elapsedNanos));
        }

        private TimingSummary add(TimingSummary other) {
            return new TimingSummary(
                    saturatingAdd(count, other.count),
                    saturatingAdd(totalNanos, other.totalNanos),
                    Math.max(maxNanos, other.maxNanos));
        }
    }

    public record EventCounts(long accepted, long dropped) {
        private static final EventCounts EMPTY = new EventCounts(0, 0);

        public EventCounts {
            if (accepted < 0 || dropped < 0) {
                throw new IllegalArgumentException("event counts must be non-negative");
            }
        }

        private EventCounts incrementAccepted() {
            return new EventCounts(saturatingAdd(accepted, 1), dropped);
        }

        private EventCounts incrementDropped() {
            return new EventCounts(accepted, saturatingAdd(dropped, 1));
        }

        private EventCounts add(EventCounts other) {
            return new EventCounts(saturatingAdd(accepted, other.accepted), saturatingAdd(dropped, other.dropped));
        }
    }

    /** Summary of event counts supplied for one Cobbleputer across execution-sync requests. */
    public record EventSyncSummary(long count, long totalEvents, long maxEvents) {
        private static final EventSyncSummary EMPTY = new EventSyncSummary(0, 0, 0);

        public EventSyncSummary {
            if (count < 0 || totalEvents < 0 || maxEvents < 0) {
                throw new IllegalArgumentException("event-sync summary values must be non-negative");
            }
        }

        private EventSyncSummary add(int eventCount) {
            return new EventSyncSummary(
                    saturatingAdd(count, 1), saturatingAdd(totalEvents, eventCount), Math.max(maxEvents, eventCount));
        }

        private EventSyncSummary add(EventSyncSummary other) {
            return new EventSyncSummary(
                    saturatingAdd(count, other.count),
                    saturatingAdd(totalEvents, other.totalEvents),
                    Math.max(maxEvents, other.maxEvents));
        }
    }

    public record EffectCounts(long attempted, long failed, TimingSummary durations) {
        private static final EffectCounts EMPTY = new EffectCounts(0, 0, TimingSummary.EMPTY);

        public EffectCounts {
            if (attempted < 0 || failed < 0 || failed > attempted || durations == null) {
                throw new IllegalArgumentException("effect counts must be valid");
            }
        }

        private EffectCounts add(long elapsedNanos, boolean failedAttempt) {
            return new EffectCounts(saturatingAdd(attempted, 1),
                    failedAttempt ? saturatingAdd(failed, 1) : failed, durations.add(elapsedNanos));
        }

        private EffectCounts add(EffectCounts other) {
            return new EffectCounts(saturatingAdd(attempted, other.attempted),
                    saturatingAdd(failed, other.failed), durations.add(other.durations));
        }
    }

    public record Snapshot(
            int windowTicks,
            long firstTick,
            long lastTick,
            int retainedTickCount,
            TimingSummary syncDurations,
            Map<SyncOutcome, Long> syncOutcomes,
            Map<EventMetricKey, EventCounts> events,
            Map<PlayerCobbleputerKey, EventSyncSummary> eventsPerSync,
            Map<EffectMetricKey, EffectCounts> effects,
            Map<PlayerCobbleputerKey, LifecycleState> cobbleputerStates) {
        public Snapshot {
            syncOutcomes = Map.copyOf(syncOutcomes);
            events = Map.copyOf(events);
            eventsPerSync = Map.copyOf(eventsPerSync);
            effects = Map.copyOf(effects);
            cobbleputerStates = Map.copyOf(cobbleputerStates);
        }
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static final class TickBucket {
        private long tick = -1;
        private TimingSummary syncDurations = TimingSummary.EMPTY;
        private final Map<SyncOutcome, Long> syncOutcomes = new HashMap<>();
        private final Map<EventMetricKey, EventCounts> events = new HashMap<>();
        private final Map<PlayerCobbleputerKey, EventSyncSummary> eventsPerSync = new HashMap<>();
        private final Map<EffectMetricKey, EffectCounts> effects = new HashMap<>();
        private Map<PlayerCobbleputerKey, LifecycleState> states = Map.of();

        private void reset(long nextTick) {
            tick = nextTick;
            syncDurations = TimingSummary.EMPTY;
            syncOutcomes.clear();
            events.clear();
            eventsPerSync.clear();
            effects.clear();
            states = Map.of();
        }
    }
}
