package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionPoolManagerTest {
    @Test
    void assignsGuaranteedPlayersByPriorityThenOnlineTime() {
        ExecutionPoolManager manager = manager(2, 1);
        UUID lowPriority = UUID.randomUUID();
        UUID newerOnline = UUID.randomUUID();
        UUID olderOnline = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(lowPriority, "low", 3, 0),
                eligible(newerOnline, "newer", 2, 50),
                eligible(olderOnline, "older", 2, 10)));
        manager.advanceBestEffortPool();

        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED,
                manager.poolFor(olderOnline).orElseThrow());
        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED,
                manager.poolFor(newerOnline).orElseThrow());
        assertEquals(
                ExecutionPoolManager.Pool.BEST_EFFORT,
                manager.poolFor(lowPriority).orElseThrow());
        assertEquals(List.of("older", "newer"), names(manager.guaranteedMembers()));
        assertEquals(List.of("low"), names(manager.bestEffortMembers()));
    }

    @Test
    void preservesTieBreakersUntilAPlayerLeavesEligibility() {
        AtomicLong random = new AtomicLong();
        ExecutionPoolManager manager = new ExecutionPoolManager(1, 0, random::incrementAndGet);
        manager.initialize(10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<ExecutionPoolManager.EligiblePlayer> tied =
                List.of(eligible(first, "first", 2, 10), eligible(second, "second", 2, 10));

        manager.refresh(tied);
        UUID firstWinner = manager.guaranteedMembers().getFirst().playerId();
        manager.refresh(tied);

        assertEquals(firstWinner, manager.guaranteedMembers().getFirst().playerId());
        assertEquals(1, manager.guaranteedMembers().size());
        assertEquals(0, manager.bestEffortMembers().size());

        manager.refresh(List.of(eligible(second, "second", 2, 10)));

        assertTrue(manager.poolFor(first).isEmpty());
        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED, manager.poolFor(second).orElseThrow());
    }

    @Test
    void resolvesDefaultGuaranteedCapacityAndLeavesQueuedPlayersWithoutAnAssignment() {
        ExecutionPoolManager manager = manager(0, 0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(first, "first", 1, 0), eligible(second, "second", 2, 0), eligible(third, "third", 3, 0)));
        manager.advanceBestEffortPool();

        assertEquals(2, manager.guaranteedPoolSize());
        assertEquals(0, manager.bestEffortPoolSize());
        assertEquals(2, manager.guaranteedMembers().size());
        assertEquals(List.of(), names(manager.bestEffortMembers()));
        assertTrue(manager.poolFor(third).isEmpty());
    }

    @Test
    void rotatesBestEffortPlayersInFifoOrderAfterTheirTickDurationExpires() {
        ExecutionPoolManager manager = manager(1, 2);
        UUID guaranteed = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(guaranteed, "guaranteed", 1, 0),
                eligible(first, "first", 2, 0),
                eligible(second, "second", 2, 1),
                eligible(third, "third", 2, 2)));
        manager.advanceBestEffortPool();

        assertEquals(List.of("first", "second"), names(manager.bestEffortMembers()));
        for (int tick = 0; tick < ExecutionPoolManager.BEST_EFFORT_POOL_DURATION_TICKS; tick += 1) {
            manager.advanceBestEffortPool();
        }

        assertEquals(List.of("third", "first"), names(manager.bestEffortMembers()));
    }

    @Test
    void removesPromotedPlayersFromBestEffortAndFillsTheirSlotFromTheQueue() {
        ExecutionPoolManager manager = manager(1, 1);
        UUID first = UUID.randomUUID();
        UUID promoted = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(first, "first", 1, 0),
                eligible(promoted, "promoted", 2, 0),
                eligible(waiting, "waiting", 3, 0)));
        manager.advanceBestEffortPool();
        assertEquals(List.of("promoted"), names(manager.bestEffortMembers()));

        manager.refresh(List.of(
                eligible(first, "first", 2, 0),
                eligible(promoted, "promoted", 1, 0),
                eligible(waiting, "waiting", 3, 0)));
        manager.advanceBestEffortPool();

        assertEquals(List.of("promoted"), names(manager.guaranteedMembers()));
        assertEquals(List.of("waiting"), names(manager.bestEffortMembers()));
        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED, manager.poolFor(promoted).orElseThrow());
    }

    private static ExecutionPoolManager manager(int guaranteedSize, int bestEffortSize) {
        ExecutionPoolManager manager = new ExecutionPoolManager(guaranteedSize, bestEffortSize, () -> 0);
        manager.initialize(2);
        return manager;
    }

    private static ExecutionPoolManager.EligiblePlayer eligible(UUID id, String name, int priority, long onlineTime) {
        return new ExecutionPoolManager.EligiblePlayer(id, name, priority, onlineTime);
    }

    private static List<String> names(List<ExecutionPoolManager.Member> members) {
        return members.stream().map(ExecutionPoolManager.Member::playerName).toList();
    }
}
