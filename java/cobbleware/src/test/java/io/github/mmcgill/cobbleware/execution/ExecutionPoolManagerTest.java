package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionPoolManagerTest {
    @Test
    void assignsAllEligiblePlayersByPriorityThenOnlineTime() {
        ExecutionPoolManager manager = manager(2);
        UUID lowPriority = UUID.randomUUID();
        UUID newerOnline = UUID.randomUUID();
        UUID olderOnline = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(lowPriority, "low", 3, 0),
                eligible(newerOnline, "newer", 2, 50),
                eligible(olderOnline, "older", 2, 10)));

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
        assertEquals(1, manager.bestEffortMembers().size());

        manager.refresh(List.of(eligible(second, "second", 2, 10)));

        assertTrue(manager.poolFor(first).isEmpty());
        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED, manager.poolFor(second).orElseThrow());
    }

    @Test
    void resolvesDefaultGuaranteedCapacityAndDoesNotCapBestEffortMembership() {
        ExecutionPoolManager manager = manager(0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        manager.refresh(List.of(
                eligible(first, "first", 1, 0), eligible(second, "second", 2, 0), eligible(third, "third", 3, 0)));

        assertEquals(2, manager.guaranteedPoolSize());
        assertEquals(0, manager.bestEffortPoolSize());
        assertEquals(2, manager.guaranteedMembers().size());
        assertEquals(List.of("third"), names(manager.bestEffortMembers()));
    }

    private static ExecutionPoolManager manager(int guaranteedSize) {
        ExecutionPoolManager manager = new ExecutionPoolManager(guaranteedSize, 0, () -> 0);
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
