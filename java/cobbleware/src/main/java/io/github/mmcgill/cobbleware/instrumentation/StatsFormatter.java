package io.github.mmcgill.cobbleware.instrumentation;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Formats immutable instrumentation snapshots for the administrative stats command. */
public final class StatsFormatter {
    private static final int CATEGORY_WIDTH = 14;
    private static final int COLUMN_WIDTH = 11;

    private StatsFormatter() {
    }

    /** A command-output line, including whether it needs Minecraft's uniform font. */
    public record Line(String text, boolean usesUniformFont) {
    }

    /** Formats every metric family in a snapshot. */
    public static List<Line> all(Instrumentation.Snapshot snapshot) {
        List<Line> lines = header("Cobbleware stats", snapshot);
        Instrumentation.EventCounts events = totalEvents(snapshot.events().values());
        Instrumentation.EffectCounts effects = totalEffects(snapshot.effects().values());
        lines.add(metricWithTiming(snapshot.syncDurations(), "Sync"));
        lines.add(metric("Events", "accepted", Long.toString(events.accepted()), "dropped", Long.toString(events.dropped())));
        lines.add(metricWithTiming(effects.durations(), "Effects", "attempted", Long.toString(effects.attempted()), "failed",
                Long.toString(effects.failed())));
        lines.add(metric("Cobbleputers", "count", Integer.toString(snapshot.cobbleputerStates().size())));
        return List.copyOf(lines);
    }

    /** Formats metrics for one player name, matching active-execution names case-insensitively. */
    public static Optional<List<Line>> player(Instrumentation.Snapshot snapshot, String playerName) {
        boolean known = snapshot.events().keySet().stream().anyMatch(key -> sameName(key.target().playerName(), playerName))
                || snapshot.effects().keySet().stream().anyMatch(key -> sameName(key.target().playerName(), playerName))
                || snapshot.cobbleputerStates().keySet().stream()
                        .anyMatch(key -> sameName(key.playerName(), playerName));
        if (!known) {
            return Optional.empty();
        }
        List<Line> lines = header("Cobbleware stats for player " + playerName, snapshot);
        appendEvents(lines, snapshot.events().entrySet().stream()
                .filter(entry -> sameName(entry.getKey().target().playerName(), playerName)).toList());
        appendEffects(lines, snapshot.effects().entrySet().stream()
                .filter(entry -> sameName(entry.getKey().target().playerName(), playerName)).toList());
        appendStates(lines, snapshot.cobbleputerStates().entrySet().stream()
                .filter(entry -> sameName(entry.getKey().playerName(), playerName)).toList());
        return Optional.of(List.copyOf(lines));
    }

    /** Formats aggregate and per-Cobbleputer event counters for one event type. */
    public static Optional<List<Line>> event(Instrumentation.Snapshot snapshot, String qualifier) {
        Optional<Event.EventCase> type = parseEvent(qualifier);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        List<Map.Entry<Instrumentation.EventMetricKey, Instrumentation.EventCounts>> matching = snapshot.events().entrySet().stream()
                .filter(entry -> entry.getKey().type() == type.orElseThrow())
                .toList();
        long accepted = matching.stream().mapToLong(entry -> entry.getValue().accepted()).sum();
        long dropped = matching.stream().mapToLong(entry -> entry.getValue().dropped()).sum();
        List<Line> lines = header("Cobbleware stats for event " + eventName(type.orElseThrow()), snapshot);
        lines.add(metric("Event total", "accepted", Long.toString(accepted), "dropped", Long.toString(dropped)));
        appendEvents(lines, matching);
        return Optional.of(List.copyOf(lines));
    }

    /** Formats all event/effect counters recorded for one durable Cobbleputer ID. */
    public static Optional<List<Line>> cobbleputer(Instrumentation.Snapshot snapshot, UUID cobbleputerId) {
        List<Map.Entry<Instrumentation.EventMetricKey, Instrumentation.EventCounts>> events = snapshot.events().entrySet().stream()
                .filter(entry -> entry.getKey().target().cobbleputer().id().equals(cobbleputerId)).toList();
        List<Map.Entry<Instrumentation.EffectMetricKey, Instrumentation.EffectCounts>> effects = snapshot.effects().entrySet().stream()
                .filter(entry -> entry.getKey().target().cobbleputer().id().equals(cobbleputerId)).toList();
        List<Map.Entry<Instrumentation.PlayerCobbleputerKey, Instrumentation.LifecycleState>> states =
                snapshot.cobbleputerStates().entrySet().stream()
                        .filter(entry -> entry.getKey().cobbleputer().id().equals(cobbleputerId)).toList();
        if (events.isEmpty() && effects.isEmpty() && states.isEmpty()) {
            return Optional.empty();
        }
        Instrumentation.CobbleputerKey key = !events.isEmpty()
                ? events.getFirst().getKey().target().cobbleputer()
                : !effects.isEmpty()
                        ? effects.getFirst().getKey().target().cobbleputer()
                        : states.getFirst().getKey().cobbleputer();
        List<Line> lines = header("Cobbleware stats for Cobbleputer " + cobbleputerId, snapshot);
        lines.add(metric("Location", location(key)));
        appendEvents(lines, events);
        appendEffects(lines, effects);
        appendStates(lines, states);
        return Optional.of(List.copyOf(lines));
    }

    /** Returns all currently selectable player, event, and Cobbleputer qualifiers. */
    public static List<String> qualifiers(Instrumentation.Snapshot snapshot) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(
                                snapshot.events().keySet().stream().map(key -> key.target().playerName()),
                                snapshot.effects().keySet().stream().map(key -> key.target().playerName())),
                        java.util.stream.Stream.concat(
                                snapshot.cobbleputerStates().keySet().stream().map(Instrumentation.PlayerCobbleputerKey::playerName),
                                java.util.stream.Stream.concat(
                                        java.util.Arrays.stream(Event.EventCase.values())
                                                .filter(type -> type != Event.EventCase.EVENT_NOT_SET)
                                                .map(StatsFormatter::eventName),
                                        java.util.stream.Stream.concat(
                                                snapshot.events().keySet().stream()
                                                        .map(key -> key.target().cobbleputer().id().toString()),
                                                java.util.stream.Stream.concat(
                                                        snapshot.effects().keySet().stream()
                                                                .map(key -> key.target().cobbleputer().id().toString()),
                                                        snapshot.cobbleputerStates().keySet().stream()
                                                                .map(key -> key.cobbleputer().id().toString()))))))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static List<Line> header(String title, Instrumentation.Snapshot snapshot) {
        return new ArrayList<>(List.of(
                heading(title),
                heading("Window: " + snapshot.retainedTickCount() + "/" + snapshot.windowTicks()
                        + " ticks (" + snapshot.firstTick() + "-" + snapshot.lastTick() + ")")));
    }

    private static void appendEvents(
            List<Line> lines, Map<Instrumentation.EventMetricKey, Instrumentation.EventCounts> events) {
        appendEvents(lines, events.entrySet());
    }

    private static void appendEvents(
            List<Line> lines,
            java.util.Collection<Map.Entry<Instrumentation.EventMetricKey, Instrumentation.EventCounts>> events) {
        if (events.isEmpty()) {
            lines.add(heading("Events: none"));
            return;
        }
        lines.add(heading("Events:"));
        events.stream().sorted(Comparator
                        .comparing((Map.Entry<Instrumentation.EventMetricKey, Instrumentation.EventCounts> entry) ->
                                entry.getKey().target().playerName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.getKey().target().cobbleputer().id())
                        .thenComparing(entry -> entry.getKey().type().name()))
                .forEach(entry -> lines.add(metric(eventName(entry.getKey().type()), "player",
                        entry.getKey().target().playerName(), "cobbleputer",
                        entry.getKey().target().cobbleputer().id().toString(), "accepted",
                        Long.toString(entry.getValue().accepted()), "dropped", Long.toString(entry.getValue().dropped()))));
    }

    private static void appendEffects(
            List<Line> lines, Map<Instrumentation.EffectMetricKey, Instrumentation.EffectCounts> effects) {
        appendEffects(lines, effects.entrySet());
    }

    private static void appendEffects(
            List<Line> lines,
            java.util.Collection<Map.Entry<Instrumentation.EffectMetricKey, Instrumentation.EffectCounts>> effects) {
        if (effects.isEmpty()) {
            lines.add(heading("Effects: none"));
            return;
        }
        lines.add(heading("Effects:"));
        effects.stream().sorted(Comparator
                        .comparing((Map.Entry<Instrumentation.EffectMetricKey, Instrumentation.EffectCounts> entry) ->
                                entry.getKey().target().playerName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.getKey().target().cobbleputer().id())
                        .thenComparing(entry -> entry.getKey().type().name()))
                .forEach(entry -> lines.add(metricWithTiming(entry.getValue().durations(), effectName(entry.getKey().type()), "player",
                        entry.getKey().target().playerName(), "cobbleputer",
                        entry.getKey().target().cobbleputer().id().toString(), "attempted",
                        Long.toString(entry.getValue().attempted()), "failed", Long.toString(entry.getValue().failed()))));
    }

    private static void appendStates(
            List<Line> lines, Map<Instrumentation.PlayerCobbleputerKey, Instrumentation.LifecycleState> states) {
        appendStates(lines, states.entrySet());
    }

    private static void appendStates(
            List<Line> lines,
            java.util.Collection<Map.Entry<Instrumentation.PlayerCobbleputerKey, Instrumentation.LifecycleState>> states) {
        if (states.isEmpty()) {
            lines.add(heading("Cobbleputers: none"));
            return;
        }
        Map<Instrumentation.PlayerStateKey, Integer> grouped = new java.util.HashMap<>();
        for (Map.Entry<Instrumentation.PlayerCobbleputerKey, Instrumentation.LifecycleState> entry : states) {
            Instrumentation.PlayerCobbleputerKey target = entry.getKey();
            grouped.merge(new Instrumentation.PlayerStateKey(
                    target.playerId(), target.playerName(), entry.getValue()), 1, Integer::sum);
        }
        lines.add(heading("Cobbleputers:"));
        grouped.entrySet().stream().sorted(Comparator
                        .comparing((Map.Entry<Instrumentation.PlayerStateKey, Integer> entry) ->
                                entry.getKey().playerName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.getKey().state().name()))
                .forEach(entry -> lines.add(metric("player", entry.getKey().playerName(), "state",
                        entry.getKey().state().name().toLowerCase(Locale.ROOT), "count",
                        Integer.toString(entry.getValue()))));
    }

    private static Optional<Event.EventCase> parseEvent(String qualifier) {
        String normalized = qualifier.replace('-', '_').toUpperCase(Locale.ROOT);
        for (Event.EventCase type : Event.EventCase.values()) {
            if (type != Event.EventCase.EVENT_NOT_SET && type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static boolean sameName(String left, String right) {
        return left.equalsIgnoreCase(right);
    }

    private static String eventName(Event.EventCase type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String effectName(Effect.EffectCase type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String location(Instrumentation.CobbleputerKey key) {
        return key.dimension() + " " + key.x() + "," + key.y() + "," + key.z();
    }

    private static String[] timingColumns(Instrumentation.TimingSummary timing) {
        if (timing.count() == 0) {
            return new String[] {"count", "0"};
        }
        return new String[] {"count", Long.toString(timing.count()), "avg",
                formatMillis(timing.totalNanos() / timing.count()), "max", formatMillis(timing.maxNanos())};
    }

    private static Instrumentation.EventCounts totalEvents(
            java.util.Collection<Instrumentation.EventCounts> counts) {
        return counts.stream().reduce(new Instrumentation.EventCounts(0, 0),
                (left, right) -> new Instrumentation.EventCounts(
                        Math.addExact(left.accepted(), right.accepted()), Math.addExact(left.dropped(), right.dropped())));
    }

    private static Instrumentation.EffectCounts totalEffects(
            java.util.Collection<Instrumentation.EffectCounts> counts) {
        return counts.stream().reduce(new Instrumentation.EffectCounts(
                        0, 0, new Instrumentation.TimingSummary(0, 0, 0)),
                (left, right) -> new Instrumentation.EffectCounts(
                        Math.addExact(left.attempted(), right.attempted()), Math.addExact(left.failed(), right.failed()),
                        new Instrumentation.TimingSummary(
                                Math.addExact(left.durations().count(), right.durations().count()),
                                Math.addExact(left.durations().totalNanos(), right.durations().totalNanos()),
                                Math.max(left.durations().maxNanos(), right.durations().maxNanos()))));
    }

    private static Line heading(String text) {
        return new Line(text, false);
    }

    private static Line metric(String... columns) {
        return new Line(row(columns), true);
    }

    private static Line metricWithTiming(Instrumentation.TimingSummary timing, String... columns) {
        String[] timingColumns = timingColumns(timing);
        String[] allColumns = java.util.Arrays.copyOf(columns, columns.length + timingColumns.length);
        System.arraycopy(timingColumns, 0, allColumns, columns.length, timingColumns.length);
        return metric(allColumns);
    }

    private static String row(String... columns) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (index == columns.length - 1) {
                result.append(column);
            } else {
                result.append(pad(column, index == 0 ? CATEGORY_WIDTH : COLUMN_WIDTH));
            }
        }
        return result.toString();
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(1, width - value.length()));
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }
}
