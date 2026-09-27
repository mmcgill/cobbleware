package io.github.mmcgill.cobbleware.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsFormatterTest {
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID COBBLEPUTER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    private Instrumentation.Snapshot snapshot;

    @BeforeEach
    void createSnapshot() {
        Instrumentation instrumentation = new Instrumentation(10);
        Instrumentation.CobbleputerKey cobbleputer =
                new Instrumentation.CobbleputerKey(COBBLEPUTER_ID, "minecraft:overworld", 12, 64, -5);
        Instrumentation.PlayerCobbleputerKey target =
                new Instrumentation.PlayerCobbleputerKey(PLAYER_ID, "Alice", cobbleputer);
        instrumentation.advanceTick(1);
        instrumentation.recordSyncCompletion(2_000_000, Instrumentation.SyncOutcome.SUCCESS);
        instrumentation.recordEvent(target, Event.EventCase.CHAT_MESSAGE, true);
        instrumentation.recordEvent(target, Event.EventCase.CHAT_MESSAGE, false);
        instrumentation.recordEventsPerSync(target, 4);
        instrumentation.recordEffect(target, Effect.EffectCase.SEND_CHAT, 500_000, true);
        instrumentation.recordCobbleputerStates(Map.of(
                target, new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.RUNNING, 3)));
        snapshot = instrumentation.snapshot();
    }

    @Test
    void formatsAllMetricFamilies() {
        List<StatsFormatter.Line> lines = StatsFormatter.all(snapshot);
        List<String> text = texts(lines);

        assertTrue(text.contains("Cobbleware stats"));
        assertTrue(text.contains("Sync          count      1          avg        2.0ms      max        2.0ms"));
        assertTrue(text.stream()
                .anyMatch(line -> line.startsWith("Events") && line.contains("accepted") && line.contains("dropped")));
        assertTrue(text.stream()
                .anyMatch(line -> line.startsWith("Events/sync")
                        && line.contains("avg")
                        && line.contains("4.0")
                        && line.contains("max")));
        assertTrue(text.stream()
                .anyMatch(line -> line.startsWith("Effects") && line.contains("attempted") && line.contains("0.5ms")));
        assertTrue(text.stream().anyMatch(line -> line.startsWith("Cobbleputers") && line.contains("count")));
        assertFalse(text.stream().anyMatch(line -> line.contains(COBBLEPUTER_ID.toString())));
        assertTrue(lines.getFirst().usesUniformFont() == false);
        assertTrue(lines.stream().skip(2).allMatch(StatsFormatter.Line::usesUniformFont));
        assertFalse(text.stream().anyMatch(line -> line.contains("\t")));
    }

    @Test
    void filtersByTheDisjointQualifierTypes() {
        List<String> player = texts(StatsFormatter.player(snapshot, "aLiCe").orElseThrow());
        assertTrue(player.getFirst().contains("player aLiCe"));
        assertFalse(player.stream().anyMatch(line -> line.startsWith("Sync:")));
        assertTrue(
                player.stream().anyMatch(line -> line.startsWith("Events/sync:") || line.startsWith("Events/sync  ")));

        List<String> event =
                texts(StatsFormatter.event(snapshot, "chat-message").orElseThrow());
        assertTrue(event.stream()
                .anyMatch(line ->
                        line.startsWith("Event total") && line.contains("accepted") && line.contains("dropped")));
        assertFalse(StatsFormatter.event(snapshot, "Alice").isPresent());

        List<String> cobbleputer =
                texts(StatsFormatter.cobbleputer(snapshot, COBBLEPUTER_ID).orElseThrow());
        assertTrue(cobbleputer.stream()
                .anyMatch(line -> line.startsWith("Location") && line.contains("minecraft:overworld 12,64,-5")));
        assertTrue(cobbleputer.stream()
                .anyMatch(line -> line.startsWith("player")
                        && line.contains("Alice")
                        && line.contains("running")
                        && line.contains("count")));
        assertTrue(cobbleputer.stream()
                .anyMatch(line -> line.startsWith("Events/sync:") || line.startsWith("Events/sync  ")));
        assertFalse(StatsFormatter.cobbleputer(snapshot, UUID.randomUUID()).isPresent());
    }

    @Test
    void suggestsKnownPlayerEventAndCobbleputerQualifiers() {
        List<String> qualifiers = StatsFormatter.qualifiers(snapshot);

        assertTrue(qualifiers.contains("Alice"));
        assertTrue(qualifiers.contains("chat-message"));
        assertTrue(qualifiers.contains(COBBLEPUTER_ID.toString()));
        assertEquals(qualifiers.stream().distinct().count(), qualifiers.size());
    }

    private static List<String> texts(List<StatsFormatter.Line> lines) {
        return lines.stream().map(StatsFormatter.Line::text).toList();
    }
}
