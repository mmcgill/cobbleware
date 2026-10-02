package io.github.mmcgill.cobbleware.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Maintains the current, non-durable assignment of eligible players to execution pools. */
public final class ExecutionPoolManager {
    static final int BEST_EFFORT_POOL_DURATION_TICKS = 20 * 5;

    /** The execution pools available to eligible players. */
    public enum Pool {
        GUARANTEED,
        BEST_EFFORT
    }

    /** The data used to assign one eligible player to a pool. */
    public record EligiblePlayer(UUID playerId, String playerName, int priority, long onlineTime) {
        public EligiblePlayer {
            if (playerId == null || playerName == null || playerName.isEmpty()) {
                throw new IllegalArgumentException("eligible player id and name must not be null or empty");
            }
            if (priority < PlayerPriorityStore.MIN_PRIORITY || priority > PlayerPriorityStore.MAX_PRIORITY) {
                throw new IllegalArgumentException("eligible player priority is out of range");
            }
            if (onlineTime < 0) {
                throw new IllegalArgumentException("eligible player online time must not be negative");
            }
        }
    }

    /** A player currently assigned to one of the pools. */
    public record Member(UUID playerId, String playerName) {
        public Member {
            if (playerId == null || playerName == null || playerName.isEmpty()) {
                throw new IllegalArgumentException("pool member id and name must not be null or empty");
            }
        }
    }

    private final int configuredGuaranteedPoolSize;
    private final int bestEffortPoolSize;
    private final LongSupplier randomLongs;
    private final Map<UUID, Long> randomTieBreakers = new HashMap<>();
    private Map<UUID, EligiblePlayer> eligiblePlayers = Map.of();
    private Set<UUID> guaranteedPlayerIds = Set.of();
    private final List<UUID> bestEffortPlayerIds = new ArrayList<>();
    private final LinkedHashSet<UUID> bestEffortQueue = new LinkedHashSet<>();
    private final Map<UUID, Integer> bestEffortTicksRemaining = new HashMap<>();
    private Map<UUID, Pool> assignments = Map.of();
    private List<Member> guaranteedMembers = List.of();
    private List<Member> bestEffortMembers = List.of();
    private int guaranteedPoolSize;
    private boolean initialized;

    /** Creates a manager whose default guaranteed size is resolved at server startup. */
    public ExecutionPoolManager(int configuredGuaranteedPoolSize, int bestEffortPoolSize) {
        this(
                configuredGuaranteedPoolSize,
                bestEffortPoolSize,
                () -> ThreadLocalRandom.current().nextLong());
    }

    ExecutionPoolManager(int configuredGuaranteedPoolSize, int bestEffortPoolSize, LongSupplier randomLongs) {
        if (configuredGuaranteedPoolSize < 0 || bestEffortPoolSize < 0 || randomLongs == null) {
            throw new IllegalArgumentException(
                    "execution pool sizes must be non-negative and random source must not be null");
        }
        this.configuredGuaranteedPoolSize = configuredGuaranteedPoolSize;
        this.bestEffortPoolSize = bestEffortPoolSize;
        this.randomLongs = randomLongs;
    }

    /** Resolves an omitted guaranteed-pool setting to the server's player limit. */
    public void initialize(int serverPlayerLimit) {
        if (serverPlayerLimit <= 0) {
            throw new IllegalArgumentException("server player limit must be positive");
        }
        if (initialized) {
            throw new IllegalStateException("execution pool manager is already initialized");
        }
        guaranteedPoolSize = configuredGuaranteedPoolSize == 0 ? serverPlayerLimit : configuredGuaranteedPoolSize;
        initialized = true;
    }

    /** Recomputes guaranteed-pool membership and reconciles the best-effort scheduler's eligible players. */
    public void refresh(Collection<EligiblePlayer> eligiblePlayers) {
        requireInitialized();
        if (eligiblePlayers == null) {
            throw new IllegalArgumentException("eligible players must not be null");
        }

        Map<UUID, EligiblePlayer> byId = new HashMap<>();
        for (EligiblePlayer player : eligiblePlayers) {
            if (player == null || byId.put(player.playerId(), player) != null) {
                throw new IllegalArgumentException("eligible players must be non-null and have distinct ids");
            }
        }
        randomTieBreakers.keySet().retainAll(byId.keySet());
        List<EligiblePlayer> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparingInt(EligiblePlayer::priority)
                .thenComparingLong(EligiblePlayer::onlineTime)
                .thenComparingLong(player ->
                        randomTieBreakers.computeIfAbsent(player.playerId(), ignored -> randomLongs.getAsLong()))
                .thenComparing(EligiblePlayer::playerId));

        Set<UUID> nextGuaranteedPlayerIds = new LinkedHashSet<>();
        for (int index = 0; index < ordered.size(); index += 1) {
            EligiblePlayer player = ordered.get(index);
            if (index < guaranteedPoolSize) {
                nextGuaranteedPlayerIds.add(player.playerId());
            }
        }
        this.eligiblePlayers = Map.copyOf(byId);
        guaranteedPlayerIds = Collections.unmodifiableSet(new LinkedHashSet<>(nextGuaranteedPlayerIds));

        bestEffortPlayerIds.removeIf(playerId -> !isBestEffortEligible(playerId));
        bestEffortTicksRemaining.keySet().retainAll(bestEffortPlayerIds);
        bestEffortQueue.removeIf(playerId -> !isBestEffortEligible(playerId) || bestEffortPlayerIds.contains(playerId));
        for (EligiblePlayer player : ordered) {
            UUID playerId = player.playerId();
            if (isBestEffortEligible(playerId) && !bestEffortPlayerIds.contains(playerId)) {
                bestEffortQueue.add(playerId);
            }
        }
        rebuildMembershipViews();
    }

    /** Advances active best-effort members by one server tick and fills every vacant best-effort slot. */
    void advanceBestEffortPool() {
        requireInitialized();
        List<UUID> expired = new ArrayList<>();
        for (UUID playerId : bestEffortPlayerIds) {
            int remaining = bestEffortTicksRemaining.getOrDefault(playerId, BEST_EFFORT_POOL_DURATION_TICKS) - 1;
            if (remaining <= 0) {
                expired.add(playerId);
            } else {
                bestEffortTicksRemaining.put(playerId, remaining);
            }
        }
        for (UUID playerId : expired) {
            bestEffortPlayerIds.remove(playerId);
            bestEffortTicksRemaining.remove(playerId);
            bestEffortQueue.add(playerId);
        }
        while (bestEffortPlayerIds.size() < bestEffortPoolSize && !bestEffortQueue.isEmpty()) {
            UUID playerId = bestEffortQueue.iterator().next();
            bestEffortQueue.remove(playerId);
            if (!isBestEffortEligible(playerId)) {
                continue;
            }
            bestEffortPlayerIds.add(playerId);
            bestEffortTicksRemaining.put(playerId, BEST_EFFORT_POOL_DURATION_TICKS);
        }
        rebuildMembershipViews();
    }

    /** Returns the currently configured guaranteed-pool size. */
    public int guaranteedPoolSize() {
        requireInitialized();
        return guaranteedPoolSize;
    }

    /** Returns the configured best-effort worker-slot count. */
    public int bestEffortPoolSize() {
        return bestEffortPoolSize;
    }

    /** Returns a player's current active pool, when they occupy a pool slot. */
    public Optional<Pool> poolFor(UUID playerId) {
        requireInitialized();
        if (playerId == null) {
            throw new IllegalArgumentException("player id must not be null");
        }
        return Optional.ofNullable(assignments.get(playerId));
    }

    /** Returns current guaranteed-pool members in assignment order. */
    public List<Member> guaranteedMembers() {
        requireInitialized();
        return guaranteedMembers;
    }

    /** Returns current best-effort-pool members in assignment order. */
    public List<Member> bestEffortMembers() {
        requireInitialized();
        return bestEffortMembers;
    }

    private boolean isBestEffortEligible(UUID playerId) {
        return eligiblePlayers.containsKey(playerId) && !guaranteedPlayerIds.contains(playerId);
    }

    private void rebuildMembershipViews() {
        Map<UUID, Pool> nextAssignments = new HashMap<>();
        List<Member> nextGuaranteed = new ArrayList<>();
        for (UUID playerId : guaranteedPlayerIds) {
            EligiblePlayer player = eligiblePlayers.get(playerId);
            if (player != null) {
                nextAssignments.put(playerId, Pool.GUARANTEED);
                nextGuaranteed.add(member(player));
            }
        }
        List<Member> nextBestEffort = new ArrayList<>();
        for (UUID playerId : bestEffortPlayerIds) {
            EligiblePlayer player = eligiblePlayers.get(playerId);
            if (player != null) {
                nextAssignments.put(playerId, Pool.BEST_EFFORT);
                nextBestEffort.add(member(player));
            }
        }
        assignments = Map.copyOf(nextAssignments);
        guaranteedMembers = List.copyOf(nextGuaranteed);
        bestEffortMembers = List.copyOf(nextBestEffort);
    }

    private static Member member(EligiblePlayer player) {
        return new Member(player.playerId(), player.playerName());
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("execution pool manager has not been initialized");
        }
    }
}
