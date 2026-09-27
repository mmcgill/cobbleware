package io.github.mmcgill.cobbleware.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cobbleware.effects.Effect;
import cobbleware.events.Event;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstrumentationTest {
    private static final Instrumentation.PlayerCobbleputerKey TARGET = new Instrumentation.PlayerCobbleputerKey(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            "Alice",
            new Instrumentation.CobbleputerKey(
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174001"), "minecraft:overworld", 1, 64, 2));

    @Test
    void aggregatesRetainedTicksAndEvictsTheOldestBucket() {
        Instrumentation instrumentation = new Instrumentation(2);

        instrumentation.advanceTick(1);
        instrumentation.recordSyncCompletion(3, Instrumentation.SyncOutcome.SUCCESS);
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, true);
        instrumentation.recordEventsPerSync(TARGET, 2);
        instrumentation.recordEffect(TARGET, Effect.EffectCase.SEND_CHAT, 4, false);
        instrumentation.recordCobbleputerStates(Map.of(
                TARGET, new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.RUNNING, 3)));

        instrumentation.advanceTick(2);
        instrumentation.recordSyncCompletion(7, Instrumentation.SyncOutcome.UNAVAILABLE);
        instrumentation.recordEvent(TARGET, Event.EventCase.CHAT_MESSAGE, false);
        instrumentation.recordEventsPerSync(TARGET, 4);
        instrumentation.recordEffect(TARGET, Effect.EffectCase.SEND_CHAT, 5, true);
        instrumentation.recordCobbleputerStates(Map.of(
                TARGET, new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.STOPPED, 4)));

        Instrumentation.Snapshot fullWindow = instrumentation.snapshot();
        Instrumentation.EventMetricKey eventKey =
                new Instrumentation.EventMetricKey(TARGET, Event.EventCase.CHAT_MESSAGE);
        Instrumentation.EffectMetricKey effectKey =
                new Instrumentation.EffectMetricKey(TARGET, Effect.EffectCase.SEND_CHAT);

        assertEquals(2, fullWindow.windowTicks());
        assertEquals(1, fullWindow.firstTick());
        assertEquals(2, fullWindow.lastTick());
        assertEquals(2, fullWindow.retainedTickCount());
        assertEquals(new Instrumentation.TimingSummary(2, 10, 7), fullWindow.syncDurations());
        assertEquals(
                Map.of(
                        Instrumentation.SyncOutcome.SUCCESS, 1L,
                        Instrumentation.SyncOutcome.UNAVAILABLE, 1L),
                fullWindow.syncOutcomes());
        assertEquals(new Instrumentation.EventCounts(1, 1), fullWindow.events().get(eventKey));
        assertEquals(
                new Instrumentation.EventSyncSummary(2, 6, 4),
                fullWindow.eventsPerSync().get(TARGET));
        assertEquals(
                new Instrumentation.EffectCounts(2, 1, new Instrumentation.TimingSummary(2, 9, 5)),
                fullWindow.effects().get(effectKey));
        assertEquals(
                Map.of(
                        TARGET,
                        new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.STOPPED, 4)),
                fullWindow.cobbleputerStates());
        assertThrows(
                UnsupportedOperationException.class,
                () -> fullWindow.events().put(eventKey, new Instrumentation.EventCounts(0, 0)));

        instrumentation.advanceTick(3);
        instrumentation.recordCobbleputerStates(Map.of(
                TARGET, new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.RUNNING, 5)));

        Instrumentation.Snapshot evicted = instrumentation.snapshot();
        assertEquals(2, evicted.firstTick());
        assertEquals(3, evicted.lastTick());
        assertEquals(new Instrumentation.TimingSummary(1, 7, 7), evicted.syncDurations());
        assertEquals(Map.of(Instrumentation.SyncOutcome.UNAVAILABLE, 1L), evicted.syncOutcomes());
        assertEquals(new Instrumentation.EventCounts(0, 1), evicted.events().get(eventKey));
        assertEquals(
                new Instrumentation.EventSyncSummary(1, 4, 4),
                evicted.eventsPerSync().get(TARGET));
        assertEquals(
                new Instrumentation.EffectCounts(1, 1, new Instrumentation.TimingSummary(1, 5, 5)),
                evicted.effects().get(effectKey));
        assertEquals(
                Map.of(
                        TARGET,
                        new Instrumentation.CobbleputerStateMeasurement(Instrumentation.LifecycleState.RUNNING, 5)),
                evicted.cobbleputerStates());
    }

    @Test
    void rejectsInvalidConfigurationAndMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> new Instrumentation(0));

        Instrumentation instrumentation = new Instrumentation(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> instrumentation.recordSyncCompletion(-1, Instrumentation.SyncOutcome.SUCCESS));
        assertThrows(
                IllegalArgumentException.class,
                () -> instrumentation.recordEffect(TARGET, Effect.EffectCase.SEND_CHAT, -1, false));
        assertThrows(IllegalArgumentException.class, () -> instrumentation.recordEventsPerSync(TARGET, -1));
    }
}
