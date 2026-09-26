package io.github.mmcgill.cobbleware.instrumentation;

import cobbleware.common.GlobalPos;
import cobbleware.common.Identifier;
import cobbleware.common.Uuid;
import cobbleware.common.Vec3i;
import cobbleware.effects.Effect;
import cobbleware.events.Event;
import cobbleware.internal.ExecutionStatsTick;
import cobbleware.internal.InstrumentationEffectType;
import cobbleware.internal.InstrumentationEventType;
import cobbleware.internal.InstrumentationLifecycleState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Converts one raw Mod instrumentation tick into the player-scoped reporting contract. */
public final class ExecutionStatsReporter {
    private ExecutionStatsReporter() {}

    /** Builds one complete, non-durable raw tick report. */
    public static ExecutionStatsTick report(Instrumentation.TickSnapshot snapshot, long sampledAtUnixMillis) {
        if (snapshot == null || snapshot.tick() <= 0 || sampledAtUnixMillis <= 0) {
            throw new IllegalArgumentException("raw tick snapshot and positive sample time are required");
        }
        Map<UUID, PlayerMeasurements> players = new HashMap<>();
        snapshot.events()
                .forEach((key, value) -> players.computeIfAbsent(
                                key.target().playerId(), ignored -> new PlayerMeasurements())
                        .events
                        .add(ExecutionStatsTick.EventMeasurement.newBuilder()
                                .setCobbleputer(cobbleputer(key.target().cobbleputer()))
                                .setType(eventType(key.type()))
                                .setAccepted(value.accepted())
                                .setDropped(value.dropped())
                                .build()));
        snapshot.eventsPerSync()
                .forEach((key, value) -> players.computeIfAbsent(key.playerId(), ignored -> new PlayerMeasurements())
                        .eventsPerSync
                        .add(ExecutionStatsTick.EventsPerSyncMeasurement.newBuilder()
                                .setCobbleputer(cobbleputer(key.cobbleputer()))
                                .setCount(value.count())
                                .setTotal(value.totalEvents())
                                .setMax(value.maxEvents())
                                .build()));
        snapshot.effects()
                .forEach((key, value) -> players.computeIfAbsent(
                                key.target().playerId(), ignored -> new PlayerMeasurements())
                        .effects
                        .add(ExecutionStatsTick.EffectMeasurement.newBuilder()
                                .setCobbleputer(cobbleputer(key.target().cobbleputer()))
                                .setType(effectType(key.type()))
                                .setAttempted(value.attempted())
                                .setFailed(value.failed())
                                .setTotalNanos(value.durations().totalNanos())
                                .setMaxNanos(value.durations().maxNanos())
                                .build()));
        snapshot.cobbleputerStates()
                .forEach((key, value) -> players.computeIfAbsent(key.playerId(), ignored -> new PlayerMeasurements())
                        .states
                        .add(ExecutionStatsTick.CobbleputerState.newBuilder()
                                .setCobbleputer(cobbleputer(key.cobbleputer()))
                                .setState(lifecycleState(value))
                                .build()));

        ExecutionStatsTick.Builder report = ExecutionStatsTick.newBuilder()
                .setSampledAtUnixMillis(sampledAtUnixMillis)
                .setTickSequence(snapshot.tick());
        players.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> report.addPlayers(entry.getValue().toProto(entry.getKey())));
        return report.build();
    }

    private static ExecutionStatsTick.PlayerStats toPlayer(UUID playerId, PlayerMeasurements measurements) {
        measurements.events.sort(Comparator.comparing(ExecutionStatsReporter::eventKey));
        measurements.eventsPerSync.sort(Comparator.comparing(ExecutionStatsReporter::eventsPerSyncKey));
        measurements.effects.sort(Comparator.comparing(ExecutionStatsReporter::effectKey));
        measurements.states.sort(Comparator.comparing(ExecutionStatsReporter::stateKey));
        return ExecutionStatsTick.PlayerStats.newBuilder()
                .setPlayerId(uuid(playerId))
                .addAllEvents(measurements.events)
                .addAllEventsPerSync(measurements.eventsPerSync)
                .addAllEffects(measurements.effects)
                .addAllCobbleputerStates(measurements.states)
                .build();
    }

    private static String eventKey(ExecutionStatsTick.EventMeasurement value) {
        return cobbleputerKey(value.getCobbleputer()) + ':' + value.getTypeValue();
    }

    private static String eventsPerSyncKey(ExecutionStatsTick.EventsPerSyncMeasurement value) {
        return cobbleputerKey(value.getCobbleputer());
    }

    private static String effectKey(ExecutionStatsTick.EffectMeasurement value) {
        return cobbleputerKey(value.getCobbleputer()) + ':' + value.getTypeValue();
    }

    private static String stateKey(ExecutionStatsTick.CobbleputerState value) {
        return cobbleputerKey(value.getCobbleputer());
    }

    private static String cobbleputerKey(ExecutionStatsTick.CobbleputerRef value) {
        return value.getCobbleputerId().getHigh() + ":"
                + value.getCobbleputerId().getLow() + ':'
                + value.getPosition().getDimension().getNs() + ':'
                + value.getPosition().getDimension().getPath() + ':'
                + value.getPosition().getPosition().getX() + ':'
                + value.getPosition().getPosition().getY() + ':'
                + value.getPosition().getPosition().getZ();
    }

    private static ExecutionStatsTick.CobbleputerRef cobbleputer(Instrumentation.CobbleputerKey value) {
        int separator = value.dimension().indexOf(':');
        if (separator <= 0 || separator == value.dimension().length() - 1) {
            throw new IllegalArgumentException("Cobbleputer dimension must be a namespaced identifier");
        }
        return ExecutionStatsTick.CobbleputerRef.newBuilder()
                .setCobbleputerId(uuid(value.id()))
                .setPosition(GlobalPos.newBuilder()
                        .setDimension(Identifier.newBuilder()
                                .setNs(value.dimension().substring(0, separator))
                                .setPath(value.dimension().substring(separator + 1)))
                        .setPosition(Vec3i.newBuilder()
                                .setX(value.x())
                                .setY(value.y())
                                .setZ(value.z())))
                .build();
    }

    private static Uuid uuid(UUID value) {
        return Uuid.newBuilder()
                .setHigh(value.getMostSignificantBits())
                .setLow(value.getLeastSignificantBits())
                .build();
    }

    private static InstrumentationEventType eventType(Event.EventCase value) {
        return switch (value) {
            case STARTED -> InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_STARTED;
            case RESUMED -> InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_RESUMED;
            case CHAT_MESSAGE -> InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_CHAT_MESSAGE;
            case BLOCK_STATE_CHANGE -> InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_BLOCK_STATE_CHANGE;
            case WIRELESS_OBSERVER_PAIRED ->
                InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_WIRELESS_OBSERVER_PAIRED;
            case WIRELESS_OBSERVER_UNPAIRED ->
                InstrumentationEventType.INSTRUMENTATION_EVENT_TYPE_WIRELESS_OBSERVER_UNPAIRED;
            case EVENT_NOT_SET -> throw new IllegalArgumentException("cannot report an unset event type");
        };
    }

    private static InstrumentationEffectType effectType(Effect.EffectCase value) {
        return switch (value) {
            case SEND_CHAT -> InstrumentationEffectType.INSTRUMENTATION_EFFECT_TYPE_SEND_CHAT;
            case EMIT_BEEP -> InstrumentationEffectType.INSTRUMENTATION_EFFECT_TYPE_EMIT_BEEP;
            case SET_REDSTONE_POWER -> InstrumentationEffectType.INSTRUMENTATION_EFFECT_TYPE_SET_REDSTONE_POWER;
            case EFFECT_NOT_SET -> throw new IllegalArgumentException("cannot report an unset effect type");
        };
    }

    private static InstrumentationLifecycleState lifecycleState(Instrumentation.LifecycleState value) {
        return switch (value) {
            case RUNNING -> InstrumentationLifecycleState.INSTRUMENTATION_LIFECYCLE_STATE_RUNNING;
            case STOPPED -> InstrumentationLifecycleState.INSTRUMENTATION_LIFECYCLE_STATE_STOPPED;
        };
    }

    private static final class PlayerMeasurements {
        private final List<ExecutionStatsTick.EventMeasurement> events = new ArrayList<>();
        private final List<ExecutionStatsTick.EventsPerSyncMeasurement> eventsPerSync = new ArrayList<>();
        private final List<ExecutionStatsTick.EffectMeasurement> effects = new ArrayList<>();
        private final List<ExecutionStatsTick.CobbleputerState> states = new ArrayList<>();

        private ExecutionStatsTick.PlayerStats toProto(UUID playerId) {
            return toPlayer(playerId, this);
        }
    }
}
