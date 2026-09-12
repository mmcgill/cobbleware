package io.github.mmcgill.cobbleware.instrumentation;

import cobbleware.common.Uuid;
import cobbleware.internal.ExecutionStats;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Converts the Mod's local instrumentation snapshot into the player-scoped reporting contract. */
public final class ExecutionStatsReporter {
    private ExecutionStatsReporter() {
    }

    /** Builds one complete, non-durable report for the supplied snapshot. */
    public static ExecutionStats report(Instrumentation.Snapshot snapshot, long sampledAtUnixMillis) {
        if (snapshot == null || sampledAtUnixMillis <= 0) {
            throw new IllegalArgumentException("snapshot and positive sample time are required");
        }
        Map<UUID, PlayerAggregate> players = new HashMap<>();
        snapshot.events().forEach((key, value) -> players.computeIfAbsent(key.target().playerId(), ignored -> new PlayerAggregate())
                .addEvents(value));
        snapshot.eventsPerSync().forEach((key, value) ->
                players.computeIfAbsent(key.playerId(), ignored -> new PlayerAggregate()).addEventsPerSync(value));
        snapshot.effects().forEach((key, value) -> players.computeIfAbsent(key.target().playerId(), ignored -> new PlayerAggregate())
                .addEffects(value));
        snapshot.cobbleputerStates().forEach((key, state) ->
                players.computeIfAbsent(key.playerId(), ignored -> new PlayerAggregate()).addState(state));

        ExecutionStats.Builder report = ExecutionStats.newBuilder()
                .setSampledAtUnixMillis(sampledAtUnixMillis)
                .setWindowTicks(snapshot.windowTicks())
                .setRetainedTickCount(snapshot.retainedTickCount());
        players.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                report.addPlayers(entry.getValue().toProto(entry.getKey())));
        return report.build();
    }

    private static final class PlayerAggregate {
        private long eventsPerSyncCount;
        private long eventsPerSyncTotal;
        private long eventsPerSyncMax;
        private long eventsAccepted;
        private long eventsDropped;
        private long effectsAttempted;
        private long effectsFailed;
        private long effectsTotalNanos;
        private long effectsMaxNanos;
        private int runningCobbleputers;
        private int stoppedCobbleputers;

        private void addEvents(Instrumentation.EventCounts events) {
            eventsAccepted = saturatingAdd(eventsAccepted, events.accepted());
            eventsDropped = saturatingAdd(eventsDropped, events.dropped());
        }

        private void addEventsPerSync(Instrumentation.EventSyncSummary summary) {
            eventsPerSyncCount = saturatingAdd(eventsPerSyncCount, summary.count());
            eventsPerSyncTotal = saturatingAdd(eventsPerSyncTotal, summary.totalEvents());
            eventsPerSyncMax = Math.max(eventsPerSyncMax, summary.maxEvents());
        }

        private void addEffects(Instrumentation.EffectCounts effects) {
            effectsAttempted = saturatingAdd(effectsAttempted, effects.attempted());
            effectsFailed = saturatingAdd(effectsFailed, effects.failed());
            effectsTotalNanos = saturatingAdd(effectsTotalNanos, effects.durations().totalNanos());
            effectsMaxNanos = Math.max(effectsMaxNanos, effects.durations().maxNanos());
        }

        private void addState(Instrumentation.LifecycleState state) {
            if (state == Instrumentation.LifecycleState.RUNNING) {
                runningCobbleputers = saturatingAdd(runningCobbleputers, 1);
            } else {
                stoppedCobbleputers = saturatingAdd(stoppedCobbleputers, 1);
            }
        }

        private ExecutionStats.PlayerStats toProto(UUID playerId) {
            return ExecutionStats.PlayerStats.newBuilder()
                    .setPlayerId(Uuid.newBuilder()
                            .setHigh(playerId.getMostSignificantBits())
                            .setLow(playerId.getLeastSignificantBits()))
                    .setEventsPerSyncCount(eventsPerSyncCount)
                    .setEventsPerSyncTotal(eventsPerSyncTotal)
                    .setEventsPerSyncMax(eventsPerSyncMax)
                    .setEventsAccepted(eventsAccepted)
                    .setEventsDropped(eventsDropped)
                    .setEffectsAttempted(effectsAttempted)
                    .setEffectsFailed(effectsFailed)
                    .setEffectsTotalNanos(effectsTotalNanos)
                    .setEffectsMaxNanos(effectsMaxNanos)
                    .setRunningCobbleputers(runningCobbleputers)
                    .setStoppedCobbleputers(stoppedCobbleputers)
                    .build();
        }

        private static long saturatingAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }

        private static int saturatingAdd(int left, int right) {
            return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
        }
    }
}
