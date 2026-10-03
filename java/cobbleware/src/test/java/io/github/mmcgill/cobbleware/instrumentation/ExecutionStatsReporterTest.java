package io.github.mmcgill.cobbleware.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import cobbleware.internal.ExecutionStatsTick;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionStatsReporterTest {
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final Instrumentation.PlayerCobbleputerKey TARGET = new Instrumentation.PlayerCobbleputerKey(
            PLAYER,
            "Alice",
            new Instrumentation.CobbleputerKey(
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), "minecraft:overworld", 1, 2, 3));

    @Test
    void reportsRawPlayerMeasurementsFromOneTick() {
        Instrumentation instrumentation = new Instrumentation(20);
        instrumentation.advanceTick();
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, true);
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, false);
        instrumentation.recordEventsPerSync(TARGET, 3);
        instrumentation.recordEffect(TARGET, Effect.EffectCase.SEND_CHAT, 4, true);
        instrumentation.recordCobbleputerStates(Map.of(
                TARGET, new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.RUNNING, 12)));

        ExecutionStatsTick report = ExecutionStatsReporter.report(
                instrumentation.currentTickSnapshot(),
                1234,
                Map.of(),
                Map.of(PLAYER, new io.github.mmcgill.cobbleware.execution.ExecutionStateAccess.ChatTokens(4, 96)));

        assertEquals(1234, report.getSampledAtUnixMillis());
        assertEquals(1, report.getTickSequence());
        assertEquals(1, report.getPlayersCount());
        ExecutionStatsTick.PlayerStats player = report.getPlayers(0);
        assertEquals(PLAYER.getMostSignificantBits(), player.getPlayerId().getHigh());
        assertEquals(PLAYER.getLeastSignificantBits(), player.getPlayerId().getLow());
        assertEquals(1, player.getEventsCount());
        assertEquals(1, player.getEvents(0).getAccepted());
        assertEquals(1, player.getEvents(0).getDropped());
        assertEquals(1, player.getEventsPerSyncCount());
        assertEquals(3, player.getEventsPerSync(0).getTotal());
        assertEquals(1, player.getEffectsCount());
        assertEquals(1, player.getEffects(0).getAttempted());
        assertEquals(1, player.getEffects(0).getFailed());
        assertEquals(1, player.getCobbleputerStatesCount());
        assertEquals(12, player.getCobbleputerStates(0).getStateSizeBytes());
        assertEquals(4, player.getBurstChatTokens());
        assertEquals(96, player.getSlowChatTokens());
    }
}
