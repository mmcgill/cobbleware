package io.github.mmcgill.cobbleware.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Maintains the current, non-durable assignment of eligible players to execution pools. */
public final class ExecutionPoolManager {
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

    /** Recomputes pool membership from the complete current eligible-player set. */
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

        Map<UUID, Pool> nextAssignments = new HashMap<>();
        List<Member> nextGuaranteed = new ArrayList<>();
        List<Member> nextBestEffort = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index += 1) {
            EligiblePlayer player = ordered.get(index);
            Member member = new Member(player.playerId(), player.playerName());
            if (index < guaranteedPoolSize) {
                nextAssignments.put(player.playerId(), Pool.GUARANTEED);
                nextGuaranteed.add(member);
            } else {
                nextAssignments.put(player.playerId(), Pool.BEST_EFFORT);
                nextBestEffort.add(member);
            }
        }
        assignments = Map.copyOf(nextAssignments);
        guaranteedMembers = List.copyOf(nextGuaranteed);
        bestEffortMembers = List.copyOf(nextBestEffort);
    }

    /** Returns the currently configured guaranteed-pool size. */
    public int guaranteedPoolSize() {
        requireInitialized();
        return guaranteedPoolSize;
    }

    /** Returns the configured best-effort worker-slot count for a future scheduling increment. */
    public int bestEffortPoolSize() {
        return bestEffortPoolSize;
    }

    /** Returns a player's current pool, when they are eligible. */
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

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("execution pool manager has not been initialized");
        }
    }
}
