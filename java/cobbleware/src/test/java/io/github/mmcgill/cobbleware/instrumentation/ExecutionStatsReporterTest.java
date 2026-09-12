package io.github.mmcgill.cobbleware.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import cobbleware.internal.ExecutionStats;
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
    void aggregatesPlayerMetricsAndCurrentStateGauge() {
        Instrumentation instrumentation = new Instrumentation(20);
        instrumentation.advanceTick(1);
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, true);
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, false);
        instrumentation.recordEventsPerSync(TARGET, 3);
        instrumentation.recordEffect(TARGET, Effect.EffectCase.SEND_CHAT, 4, true);
        instrumentation.recordCobbleputerStates(Map.of(TARGET, Instrumentation.LifecycleState.RUNNING));

        ExecutionStats report = ExecutionStatsReporter.report(instrumentation.snapshot(), 1234);

        assertEquals(1234, report.getSampledAtUnixMillis());
        assertEquals(20, report.getWindowTicks());
        assertEquals(1, report.getRetainedTickCount());
        assertEquals(1, report.getPlayersCount());
        ExecutionStats.PlayerStats player = report.getPlayers(0);
        assertEquals(PLAYER.getMostSignificantBits(), player.getPlayerId().getHigh());
        assertEquals(PLAYER.getLeastSignificantBits(), player.getPlayerId().getLow());
        assertEquals(1, player.getEventsPerSyncCount());
        assertEquals(3, player.getEventsPerSyncTotal());
        assertEquals(3, player.getEventsPerSyncMax());
        assertEquals(1, player.getEventsAccepted());
        assertEquals(1, player.getEventsDropped());
        assertEquals(1, player.getEffectsAttempted());
        assertEquals(1, player.getEffectsFailed());
        assertEquals(4, player.getEffectsTotalNanos());
        assertEquals(4, player.getEffectsMaxNanos());
        assertEquals(1, player.getRunningCobbleputers());
        assertEquals(0, player.getStoppedCobbleputers());
    }
}
