package io.github.mmcgill.cobbleware.execution;

import cobbleware.common.Uuid;
import cobbleware.effects.Effect;
import cobbleware.events.BlockInfo;
import cobbleware.events.BlockStateChange;
import cobbleware.events.ChatMessage;
import cobbleware.events.Event;
import cobbleware.events.Resumed;
import cobbleware.events.Started;
import cobbleware.events.WirelessObserverPaired;
import cobbleware.events.WirelessObserverUnpaired;
import cobbleware.internal.ExecutionFailureReason;
import cobbleware.internal.ExecutionPoolTransition;
import cobbleware.internal.ExecutionResult;
import cobbleware.internal.ExecutionState;
import cobbleware.internal.ExecutionTransition;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import cobbleware.internal.PlayerExecutionState;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.api.MainWorker;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlock;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.instrumentation.ExecutionStatsReporter;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Drives asynchronous synchronization of active-pool Cobbleputer executions. */
public final class ExecutionDriver {
    private static final int OUTBOX_LIMIT = 20;
    private static final double CHAT_RADIUS = 16.0;
    private static final double CHAT_RADIUS_SQUARED = CHAT_RADIUS * CHAT_RADIUS;
    private static final Comparator<CobbleputerBlockEntity> RUNNING_LIMIT_CANDIDATE_ORDER = Comparator.comparing(
                    ExecutionDriver::dimensionKey)
            .thenComparingInt(blockEntity -> blockEntity.getBlockPos().getX())
            .thenComparingInt(blockEntity -> blockEntity.getBlockPos().getY())
            .thenComparingInt(blockEntity -> blockEntity.getBlockPos().getZ())
            .thenComparing(CobbleputerBlockEntity::cobbleputerId);

    /** Outcome of an attempt to make a Cobbleputer execution running. */
    public enum ExecutionInitResult {
        SUCCESS,
        LIMIT_REACHED,
        INVALID_STATE
    }

    /**
     * Minecraft services needed while advancing executions that cannot be obtained from an individual block entity.
     *
     * <p>The driver deliberately retains Minecraft value and block-entity types at this boundary: they are useful
     * representations in unit tests and avoid duplicating Minecraft's data model. Implementations must not load a chunk
     * while resolving a Cobbleputer.
     */
    public interface Minecraft {
        Optional<CobbleputerBlockEntity> loadedCobbleputer(net.minecraft.core.GlobalPos target);

        /** Returns whether the entity is attached to a server level. */
        boolean isServerLevel(BlockEntity blockEntity);

        /** Returns the game time for an entity attached to a server level. */
        OptionalLong gameTime(CobbleputerBlockEntity blockEntity);

        /** Returns a global position in an entity's server level. */
        Optional<net.minecraft.core.GlobalPos> globalPos(BlockEntity blockEntity, BlockPos pos);

        /** Returns whether a position in an entity's server level is already loaded. */
        boolean isLoaded(BlockEntity blockEntity, BlockPos pos);

        /** Captures and advances an observer's durable block-state baseline. */
        Optional<WirelessObserverBlockEntity.ObservedBlockStateChange> takeObservedStateChange(
                WirelessObserverBlockEntity observer);

        /** Initializes an observer's durable block-state baseline when its observed position is loaded. */
        boolean initializeObservedState(WirelessObserverBlockEntity observer);

        /** Returns the color of a valid Wireless Observer block entity. */
        Optional<DyeColor> observerColor(WirelessObserverBlockEntity observer);

        /** Updates the visible lifecycle state of a Cobbleputer. */
        boolean setCobbleputerState(CobbleputerBlockEntity blockEntity, CobbleputerState nextState);

        void sendSystemMessage(UUID playerId, Component message);

        /** Delivers a system message to players near a Cobbleputer. */
        void sendNearbySystemMessage(CobbleputerBlockEntity blockEntity, Component message);

        /** Applies a Wireless Observer redstone-power effect for a Cobbleputer. */
        void setRedstonePower(
                CobbleputerBlockEntity blockEntity,
                Optional<UUID> observerId,
                Optional<DyeColor> observerColor,
                int powerLevel);
    }

    /** Returns the standard Minecraft-server-backed implementation used by the server tick callback. */
    public static Minecraft minecraft(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server must not be null");
        }
        return new ServerMinecraft(server);
    }

    private final MainWorker mainWorker;
    private final ExecutionStateAccess executionStateAccess;
    private final Instrumentation instrumentation;
    private final PlayerPriorityStore playerPriorityStore;
    private final ExecutionPoolManager executionPoolManager;
    private final int maxRunningExecutionsPerPlayer;
    private final int maxQueuedEventsPerPlayer;
    private final int maxPendingEffectsPerPlayer;
    private final long maxTotalExecutionStateBytesPerPlayer;
    private final Logger logger;
    private final Map<CobbleputerBlockEntity, EphemeralCobbleputerState> loadedCobbleputers = new IdentityHashMap<>();
    private final Map<UUID, Integer> queuedEventsByOwner = new HashMap<>();
    private Map<UUID, ExecutionPoolTransition> publishedPoolTransitions = Map.of();
    private final Set<WirelessObserverBlockEntity> pendingObserverInitializations =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CobbleputerBlockEntity> pendingChunkLoadResumes =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CobbleputerBlockEntity> pendingChunkNonTickingTransitions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<WirelessObserverBlockEntity> pendingObserverChanges =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConcurrentLinkedQueue<SyncCompletion> completedSyncMeasurements = new ConcurrentLinkedQueue<>();

    private CompletableFuture<MainWorker.ExecutionSyncResult> inFlight;
    private Flight flight;
    private boolean runtimeMayHaveExecutions;

    public ExecutionDriver(
            MainWorker mainWorker,
            ExecutionStateAccess executionStateAccess,
            Instrumentation instrumentation,
            PlayerPriorityStore playerPriorityStore,
            ExecutionPoolManager executionPoolManager,
            int maxRunningExecutionsPerPlayer,
            int maxQueuedEventsPerPlayer,
            int maxPendingEffectsPerPlayer,
            long maxTotalExecutionStateBytesPerPlayer,
            Logger logger) {
        if (mainWorker == null
                || executionStateAccess == null
                || instrumentation == null
                || playerPriorityStore == null
                || executionPoolManager == null
                || logger == null
                || maxRunningExecutionsPerPlayer <= 0
                || maxQueuedEventsPerPlayer <= 0
                || maxPendingEffectsPerPlayer <= 0
                || maxTotalExecutionStateBytesPerPlayer <= 0) {
            throw new IllegalArgumentException(
                    "ExecutionDriver dependencies must not be null and execution limits must be positive");
        }
        this.mainWorker = mainWorker;
        this.executionStateAccess = executionStateAccess;
        this.instrumentation = instrumentation;
        this.playerPriorityStore = playerPriorityStore;
        this.executionPoolManager = executionPoolManager;
        this.maxRunningExecutionsPerPlayer = maxRunningExecutionsPerPlayer;
        this.maxQueuedEventsPerPlayer = maxQueuedEventsPerPlayer;
        this.maxPendingEffectsPerPlayer = maxPendingEffectsPerPlayer;
        this.maxTotalExecutionStateBytesPerPlayer = maxTotalExecutionStateBytesPerPlayer;
        this.logger = logger;
    }

    /** Recomputes non-durable execution-pool membership from loaded running Cobbleputers. */
    public void refreshExecutionPools(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server must not be null");
        }
        Map<UUID, ExecutionPoolManager.EligiblePlayer> eligible = new HashMap<>();
        for (CobbleputerBlockEntity blockEntity : loadedCobbleputers.keySet()) {
            if (!isRunningExecution(blockEntity)) {
                continue;
            }
            UUID ownerId = blockEntity.ownerPlayerId().orElseThrow();
            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(ownerId);
            PlayerPriorityStore.Priorities priorities = playerPriorityStore.isAvailable()
                    ? playerPriorityStore.priorities(ownerId)
                    : PlayerPriorityStore.DEFAULT_PRIORITIES;
            int priority = onlinePlayer == null ? priorities.offlinePriority() : priorities.onlinePriority();
            long onlineTime = onlinePlayer == null
                    ? Long.MAX_VALUE
                    : Math.max(0, onlinePlayer.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME));
            String ownerName = ownerName(blockEntity, ownerId);
            eligible.merge(
                    ownerId,
                    new ExecutionPoolManager.EligiblePlayer(ownerId, ownerName, priority, onlineTime),
                    (first, second) -> first.playerName().compareTo(second.playerName()) <= 0 ? first : second);
        }
        refreshExecutionPools(eligible.values());
    }

    /** Refreshes and reports pool assignments for the supplied eligible players. */
    void refreshExecutionPools(Collection<ExecutionPoolManager.EligiblePlayer> eligiblePlayers) {
        if (eligiblePlayers == null) {
            throw new IllegalArgumentException("eligible players must not be null");
        }
        Map<UUID, ExecutionPoolManager.EligiblePlayer> eligibleById = new HashMap<>();
        for (ExecutionPoolManager.EligiblePlayer player : eligiblePlayers) {
            if (player == null || eligibleById.put(player.playerId(), player) != null) {
                throw new IllegalArgumentException("eligible players must be non-null and have distinct ids");
            }
        }
        executionPoolManager.refresh(eligibleById.values());
        executionPoolManager.advanceBestEffortPool();
        reportExecutionPoolTransitions(eligibleById.keySet());
    }

    /** Adds a loaded Cobbleputer to the driver's loaded set. */
    public void activate(CobbleputerBlockEntity blockEntity) {
        executionStateAccess.attach(blockEntity);
        pendingChunkNonTickingTransitions.remove(blockEntity);
        boolean newlyLoaded = loadedCobbleputers.putIfAbsent(blockEntity, new EphemeralCobbleputerState()) == null;
        if (newlyLoaded) {
            logger.info(
                    "Loaded Cobbleputer {} at {} in {} (state={}, owner={})",
                    blockEntity.cobbleputerId(),
                    blockEntity.getBlockPos().toShortString(),
                    dimensionKey(blockEntity),
                    blockEntity.getBlockState().getValue(CobbleputerBlock.STATE).getSerializedName(),
                    blockEntity.ownerPlayerId().map(UUID::toString).orElse("none"));
        }
        if (newlyLoaded && isChunkLoadResumeCandidate(blockEntity)) {
            pendingChunkLoadResumes.add(blockEntity);
        }
    }

    /** Removes a Cobbleputer and intentionally drops its ephemeral pending events. */
    public void deactivate(CobbleputerBlockEntity blockEntity) {
        discardPendingEvents(blockEntity, loadedCobbleputers.remove(blockEntity));
        pendingChunkLoadResumes.remove(blockEntity);
        pendingChunkNonTickingTransitions.remove(blockEntity);
    }

    /** Immediately drops a non-ticking Cobbleputer from accounting and defers its durable stop to a server tick. */
    public void deactivateForChunkBecameNonTicking(CobbleputerBlockEntity blockEntity) {
        logger.info(
                "Scheduling Cobbleputer {} to become inactive because its chunk stopped block ticking at {} in {} "
                        + "(state={}, owner={})",
                blockEntity.cobbleputerId(),
                blockEntity.getBlockPos().toShortString(),
                dimensionKey(blockEntity),
                blockEntity.getBlockState().getValue(CobbleputerBlock.STATE).getSerializedName(),
                blockEntity.ownerPlayerId().map(UUID::toString).orElse("none"));
        discardPendingEvents(blockEntity, loadedCobbleputers.remove(blockEntity));
        pendingChunkLoadResumes.remove(blockEntity);
        pendingChunkNonTickingTransitions.add(blockEntity);
    }

    /** Records a changed observer for coalesced processing on the next available server tick. */
    public void registerObserverChange(WirelessObserverBlockEntity observer) {
        if (observer != null) {
            pendingObserverChanges.add(observer);
        }
    }

    /** Schedules baseline initialization after chunk attachment has completed. */
    public void registerObserverInitialization(WirelessObserverBlockEntity observer) {
        if (observer != null) {
            pendingObserverInitializations.add(observer);
        }
    }

    /** Removes an unloaded or broken observer from the ephemeral pending-change set. */
    public void deactivate(WirelessObserverBlockEntity observer) {
        pendingObserverInitializations.remove(observer);
        pendingObserverChanges.remove(observer);
    }

    /** Delivers lifecycle events after an observer's durable pairing has been replaced. */
    public void observerPaired(
            Minecraft minecraft,
            WirelessObserverBlockEntity observer,
            net.minecraft.core.GlobalPos previousTarget,
            net.minecraft.core.GlobalPos newTarget) {
        if (minecraft == null || observer == null || newTarget == null || observer.isRemoved()) {
            return;
        }
        if (previousTarget != null && !previousTarget.equals(newTarget)) {
            deliverObserverUnpaired(minecraft, observer, previousTarget);
        }
        deliverObserverPaired(minecraft, observer, newTarget);
    }

    /** Delivers the unpair lifecycle event before a paired observer block is discarded. */
    public void observerRemoved(Minecraft minecraft, WirelessObserverBlockEntity observer) {
        if (minecraft == null || observer == null || observer.isRemoved()) {
            return;
        }
        observer.pairedCobbleputer().ifPresent(target -> deliverObserverUnpaired(minecraft, observer, target));
    }

    /** Starts a fresh execution and queues its first lifecycle event when the owner has capacity. */
    public ExecutionInitResult startExecution(
            Minecraft minecraft,
            CobbleputerBlockEntity blockEntity,
            UUID owner,
            String ownerName,
            byte[] initialState) {
        if (minecraft == null || owner == null || ownerName == null || ownerName.isEmpty() || initialState == null) {
            throw new IllegalArgumentException(
                    "execution dependencies, owner, owner name, and initial state must not be null or empty");
        }
        OptionalLong gameTime = minecraft.gameTime(blockEntity);
        if (gameTime.isEmpty()) {
            return ExecutionInitResult.INVALID_STATE;
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(owner)) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        discardPendingEvents(blockEntity, loadedCobbleputers.get(blockEntity));
        blockEntity.startExecution(owner, ownerName, initialState);
        if (minecraft.setCobbleputerState(blockEntity, CobbleputerState.RUNNING)) {
            enqueueEvent(
                    blockEntity,
                    Event.newBuilder()
                            .setStarted(Started.newBuilder().setTick(gameTime.getAsLong()))
                            .build());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STARTED);
            return ExecutionInitResult.SUCCESS;
        }
        return ExecutionInitResult.INVALID_STATE;
    }

    /** Stops a running execution without discarding its execution record or queued events. */
    public void stop(Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        if (minecraft == null
                || !blockEntity.hasExecution()
                || blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            return;
        }
        activate(blockEntity);
        OptionalLong gameTime = minecraft.gameTime(blockEntity);
        if (gameTime.isPresent() && minecraft.setCobbleputerState(blockEntity, CobbleputerState.STOPPED)) {
            blockEntity.setStoppedAtTick(gameTime.getAsLong());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STOPPED);
        }
    }

    /** Resumes a stopped execution and queues its resume lifecycle event when the owner has capacity. */
    public ExecutionInitResult resume(Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        if (minecraft == null
                || !blockEntity.hasExecution()
                || blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.STOPPED) {
            return ExecutionInitResult.INVALID_STATE;
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(blockEntity.ownerPlayerId().orElseThrow())) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        return transitionToRunningAndQueueResumed(minecraft, blockEntity);
    }

    /** Restarts a crashed execution with its retained state when the owner has capacity. */
    public ExecutionInitResult restartCrashedExecution(Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasRecoverableCrashedExecution()
                || blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.CRASHED) {
            return ExecutionInitResult.INVALID_STATE;
        }
        if (minecraft == null || minecraft.gameTime(blockEntity).isEmpty()) {
            return ExecutionInitResult.INVALID_STATE;
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(blockEntity.ownerPlayerId().orElseThrow())) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        EphemeralCobbleputerState active = loadedCobbleputers.get(blockEntity);
        discardPendingEvents(blockEntity, active);
        blockEntity.restartCrashedExecution();
        if (minecraft.setCobbleputerState(blockEntity, CobbleputerState.RUNNING)) {
            enqueueEvent(
                    blockEntity,
                    Event.newBuilder()
                            .setStarted(Started.newBuilder()
                                    .setTick(minecraft.gameTime(blockEntity).orElseThrow())
                                    .build())
                            .build());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STARTED);
            return ExecutionInitResult.SUCCESS;
        }
        return ExecutionInitResult.INVALID_STATE;
    }

    /** Queues an event for a running active Cobbleputer when its owner's allowance has capacity. */
    public void enqueueEvent(CobbleputerBlockEntity blockEntity, Event event) {
        if (blockEntity == null || event == null) {
            throw new IllegalArgumentException("Cobbleputer event arguments must not be null");
        }
        EphemeralCobbleputerState ephemeralState = loadedCobbleputers.get(blockEntity);
        if (ephemeralState == null || !blockEntity.hasExecution()) {
            recordRejectedEvent(blockEntity, event);
            return;
        }
        if (blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            recordRejectedEvent(blockEntity, event);
            return;
        }
        UUID owner = blockEntity.ownerPlayerId().orElseThrow();
        if (queuedEventCount(owner) >= maxQueuedEventsPerPlayer) {
            logger.debug(
                    "Dropping Cobbleputer event at {} because player {} has reached the queued-event limit of {}",
                    blockEntity.getBlockPos().toShortString(),
                    owner,
                    maxQueuedEventsPerPlayer);
            recordRejectedEvent(blockEntity, event);
            return;
        }
        try {
            appendPendingEvent(ephemeralState, owner, new QueuedEvent(blockEntity.claimNextEventSequenceNum(), event));
            recordEvent(blockEntity, event, true);
        } catch (RuntimeException exception) {
            logger.error(
                    "Could not enqueue Cobbleputer event at {}: {}",
                    blockEntity.getBlockPos().toShortString(),
                    exception.toString());
            recordRejectedEvent(blockEntity, event);
        }
    }

    /** Delivers raw player chat to every eligible active Cobbleputer in the sender's level and range. */
    public void dispatchChatMessage(ServerPlayer sender, String message) {
        Event event = Event.newBuilder()
                .setChatMessage(ChatMessage.newBuilder().setMessage(message))
                .build();
        Level senderLevel = sender.level();
        for (CobbleputerBlockEntity blockEntity : loadedCobbleputers.keySet()) {
            if (blockEntity.getLevel() != senderLevel
                    || sender.distanceToSqr(Vec3.atCenterOf(blockEntity.getBlockPos())) > CHAT_RADIUS_SQUARED) {
                continue;
            }
            enqueueEvent(blockEntity, event);
        }
    }

    /** Call once per server tick to advance execution state. */
    public void driveExecutions(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("Minecraft services must not be null");
        }
        reconcileQueuedEventCounts();
        drainChunkNonTickingTransitions(minecraft);
        drainChunkLoadResumes(minecraft);
        drainCompletedSyncMeasurements();
        drainObserverInitializations(minecraft);
        boolean syncStillPending = inFlight != null && !inFlight.isDone();
        if (inFlight != null && !syncStillPending) {
            MainWorker.ExecutionSyncResult result;
            try {
                result = inFlight.join();
            } catch (RuntimeException exception) {
                logger.warn("Execution sync completion failed: {}", exception.toString());
                result = MainWorker.ExecutionSyncResult.unavailable("Main Worker request failed");
            }
            inFlight = null;
            if (result.status() == MainWorker.ExecutionSyncStatus.SUCCESS) {
                applyResponse(minecraft, result.response(), flight);
                if (!flight.hadExecutions()) {
                    runtimeMayHaveExecutions = false;
                }
            } else {
                logger.warn("Execution sync did not succeed ({}): {}", result.status(), result.detail());
            }
            flight = null;
        }
        // We drain observer changes after processing a previous sync response specifically to ensure
        // that applied effects that produced block state change events go into the next sync request,
        // minimizing end-to-end latency.
        drainObserverChanges(minecraft);
        if (!syncStillPending) {
            Flight nextFlight = buildFlight();
            if (nextFlight.hadExecutions() || runtimeMayHaveExecutions) {
                if (nextFlight.hadExecutions()) {
                    runtimeMayHaveExecutions = true;
                }
                long dispatchedAt = System.nanoTime();
                Flight dispatchedFlight = nextFlight.withDispatchNanos(dispatchedAt);
                flight = dispatchedFlight;
                recordEventsPerSync(minecraft, dispatchedFlight);
                inFlight = mainWorker.syncExecutions(nextFlight.request);
                inFlight.whenComplete((result, failure) -> completedSyncMeasurements.add(new SyncCompletion(
                        System.nanoTime() - dispatchedFlight.dispatchNanos(), syncOutcome(result, failure))));
            }
        }
        recordCobbleputerStateGaugeAndStream(minecraft);
    }

    private void drainCompletedSyncMeasurements() {
        SyncCompletion completion;
        while ((completion = completedSyncMeasurements.poll()) != null) {
            instrumentation.recordSyncCompletion(completion.elapsedNanos, completion.outcome);
        }
    }

    /** Performs durable state changes deferred from the distance manager's chunk-status callback. */
    private void drainChunkNonTickingTransitions(Minecraft minecraft) {
        if (pendingChunkNonTickingTransitions.isEmpty()) {
            return;
        }
        List<CobbleputerBlockEntity> cobbleputers = List.copyOf(pendingChunkNonTickingTransitions);
        pendingChunkNonTickingTransitions.clear();
        for (CobbleputerBlockEntity cobbleputer : cobbleputers) {
            OptionalLong gameTime = minecraft.gameTime(cobbleputer);
            if (cobbleputer.isRemoved() || gameTime.isEmpty()) {
                continue;
            }
            boolean wasRunning = isRunningExecution(cobbleputer);
            cobbleputer.recordChunkUnloadResumeIntent(wasRunning);
            if (wasRunning && minecraft.setCobbleputerState(cobbleputer, CobbleputerState.STOPPED)) {
                cobbleputer.setStoppedAtTick(gameTime.getAsLong());
                reportExecutionTransition(cobbleputer, ExecutionTransition.EXECUTION_TRANSITION_STOPPED);
            }
        }
    }

    /**
     * Admits executions that were stopped when their chunks fell below block-ticking status and are now eligible to resume.
     * This deliberately runs on the normal server tick rather than {@link #activate}, because both changing
     * lifecycle state and consuming durable resume intent call {@code setChanged}.
     */
    private void drainChunkLoadResumes(Minecraft minecraft) {
        if (pendingChunkLoadResumes.isEmpty()) {
            return;
        }

        List<CobbleputerBlockEntity> candidates = new ArrayList<>();
        for (CobbleputerBlockEntity blockEntity : pendingChunkLoadResumes) {
            if (loadedCobbleputers.containsKey(blockEntity)
                    && isChunkLoadResumeCandidate(blockEntity)
                    && minecraft.gameTime(blockEntity).isPresent()) {
                candidates.add(blockEntity);
            }
        }
        pendingChunkLoadResumes.clear();
        candidates.sort(RUNNING_LIMIT_CANDIDATE_ORDER);

        Set<CobbleputerBlockEntity> candidateSet = Collections.newSetFromMap(new IdentityHashMap<>());
        candidateSet.addAll(candidates);
        Map<UUID, List<CobbleputerBlockEntity>> candidatesByOwner = new HashMap<>();
        for (CobbleputerBlockEntity candidate : candidates) {
            UUID owner = candidate.ownerPlayerId().orElseThrow();
            candidatesByOwner
                    .computeIfAbsent(owner, ignored -> new ArrayList<>())
                    .add(candidate);
        }

        for (Map.Entry<UUID, List<CobbleputerBlockEntity>> entry : candidatesByOwner.entrySet()) {
            int remainingCapacity = maxRunningExecutionsPerPlayer - runningExecutionCount(entry.getKey(), candidateSet);
            for (CobbleputerBlockEntity candidate : entry.getValue()) {
                if (remainingCapacity > 0
                        && resumeAfterChunkLoad(minecraft, candidate) == ExecutionInitResult.SUCCESS) {
                    remainingCapacity -= 1;
                    continue;
                }
                rejectChunkLoadResumeForRunningExecutionLimit(minecraft, candidate, entry.getKey());
            }
        }
    }

    private ExecutionInitResult resumeAfterChunkLoad(Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        if (minecraft.gameTime(blockEntity).isEmpty()
                || !blockEntity.hasExecution()
                || !blockEntity.shouldResumeAfterChunkLoad()) {
            return ExecutionInitResult.INVALID_STATE;
        }
        CobbleputerState state = blockEntity.getBlockState().getValue(CobbleputerBlock.STATE);
        if (state != CobbleputerState.STOPPED && state != CobbleputerState.RUNNING) {
            return ExecutionInitResult.INVALID_STATE;
        }
        return transitionToRunningAndQueueResumed(minecraft, blockEntity);
    }

    private ExecutionInitResult transitionToRunningAndQueueResumed(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        OptionalLong gameTime = minecraft.gameTime(blockEntity);
        if (gameTime.isEmpty()) {
            return ExecutionInitResult.INVALID_STATE;
        }
        CobbleputerState lifecycleState = blockEntity.getBlockState().getValue(CobbleputerBlock.STATE);
        if (lifecycleState == CobbleputerState.STOPPED) {
            if (!minecraft.setCobbleputerState(blockEntity, CobbleputerState.RUNNING)) {
                return ExecutionInitResult.INVALID_STATE;
            }
        } else if (lifecycleState != CobbleputerState.RUNNING) {
            return ExecutionInitResult.INVALID_STATE;
        }
        long resumeTick = gameTime.getAsLong();
        blockEntity.takeChunkUnloadResumeIntent();
        long stopTick = blockEntity.takeStoppedAtTick().orElse(resumeTick);
        enqueueEvent(
                blockEntity,
                Event.newBuilder()
                        .setResumed(Resumed.newBuilder().setStopTick(stopTick).setResumeTick(resumeTick))
                        .build());
        reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_RESUMED);
        return ExecutionInitResult.SUCCESS;
    }

    private void rejectChunkLoadResumeForRunningExecutionLimit(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity, UUID owner) {
        if (minecraft.gameTime(blockEntity).isEmpty() || !blockEntity.shouldResumeAfterChunkLoad()) {
            return;
        }
        if (isRunningExecution(blockEntity)) {
            stop(minecraft, blockEntity);
        }
        if (blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.STOPPED) {
            return;
        }
        blockEntity.takeChunkUnloadResumeIntent();
        minecraft.sendSystemMessage(
                owner,
                Component.literal("Paused your Cobbleputer execution: the running-executions-per-player limit is "
                        + maxRunningExecutionsPerPlayer + "."));
    }

    /** Returns the configured maximum number of loaded running executions for one player. */
    public int maxRunningExecutionsPerPlayer() {
        return maxRunningExecutionsPerPlayer;
    }

    private long stateSizeLimit(UUID owner, UUID cobbleputerId) {
        return executionStateAccess.stateSizeLimit(owner, cobbleputerId, maxTotalExecutionStateBytesPerPlayer);
    }

    private boolean fitsStateUpdate(UUID owner, UUID cobbleputerId, byte[] candidate) {
        return executionStateAccess.fitsStateUpdate(
                owner, cobbleputerId, candidate, maxTotalExecutionStateBytesPerPlayer);
    }

    private boolean hasRunningExecutionCapacity(UUID owner) {
        return runningExecutionCount(owner, Collections.emptySet()) < maxRunningExecutionsPerPlayer;
    }

    private int runningExecutionCount(UUID owner, Set<CobbleputerBlockEntity> excluded) {
        int count = 0;
        for (CobbleputerBlockEntity blockEntity : loadedCobbleputers.keySet()) {
            if (!excluded.contains(blockEntity)
                    && owner.equals(blockEntity.ownerPlayerId().orElse(null))
                    && isRunningExecution(blockEntity)) {
                count += 1;
            }
        }
        return count;
    }

    private int queuedEventCount(UUID owner) {
        return queuedEventsByOwner.getOrDefault(owner, 0);
    }

    private void appendPendingEvent(EphemeralCobbleputerState state, UUID owner, QueuedEvent event) {
        if (state.eventOwner != null && !state.eventOwner.equals(owner)) {
            throw new IllegalStateException("pending events cannot change owners without being discarded");
        }
        state.events.addLast(event);
        state.eventOwner = owner;
        incrementQueuedEventCount(owner);
    }

    private void removeConsumedEvents(
            CobbleputerBlockEntity blockEntity, EphemeralCobbleputerState state, long consumedSequenceNum) {
        int removed = 0;
        while (!state.events.isEmpty() && state.events.getFirst().sequenceNum <= consumedSequenceNum) {
            state.events.removeFirst();
            removed += 1;
        }
        removeQueuedEvents(blockEntity, state, removed);
    }

    private void discardPendingEvents(CobbleputerBlockEntity blockEntity, EphemeralCobbleputerState state) {
        if (state == null) {
            return;
        }
        int removed = state.events.size();
        state.events.clear();
        removeQueuedEvents(blockEntity, state, removed);
    }

    private void removeQueuedEvents(CobbleputerBlockEntity blockEntity, EphemeralCobbleputerState state, int removed) {
        if (removed == 0) {
            return;
        }
        UUID owner = state.eventOwner;
        if (owner == null) {
            throw new IllegalStateException(
                    "Cobbleputer " + blockEntity.cobbleputerId() + " has pending events without an owner");
        }
        decrementQueuedEventCount(owner, removed);
        if (state.events.isEmpty()) {
            state.eventOwner = null;
        }
    }

    private void incrementQueuedEventCount(UUID owner) {
        queuedEventsByOwner.merge(owner, 1, Math::addExact);
    }

    private void decrementQueuedEventCount(UUID owner, int removed) {
        int current = queuedEventCount(owner);
        if (removed <= 0 || current < removed) {
            throw new IllegalStateException("queued-event count is inconsistent for player " + owner);
        }
        if (current == removed) {
            queuedEventsByOwner.remove(owner);
        } else {
            queuedEventsByOwner.put(owner, current - removed);
        }
    }

    private void reconcileQueuedEventCounts() {
        Map<UUID, Integer> actual = new HashMap<>();
        for (Map.Entry<CobbleputerBlockEntity, EphemeralCobbleputerState> entry : loadedCobbleputers.entrySet()) {
            EphemeralCobbleputerState state = entry.getValue();
            if (state.events.isEmpty()) {
                if (state.eventOwner != null) {
                    logger.warn(
                            "Clearing stale pending-event owner for Cobbleputer {}",
                            entry.getKey().cobbleputerId());
                    state.eventOwner = null;
                }
                continue;
            }
            if (state.eventOwner == null) {
                state.eventOwner = entry.getKey().ownerPlayerId().orElse(null);
                if (state.eventOwner == null) {
                    logger.error(
                            "Dropping {} pending events for ownerless Cobbleputer {}",
                            state.events.size(),
                            entry.getKey().cobbleputerId());
                    state.events.clear();
                    continue;
                }
                logger.warn(
                        "Recovered missing pending-event owner {} for Cobbleputer {}",
                        state.eventOwner,
                        entry.getKey().cobbleputerId());
            }
            actual.merge(state.eventOwner, state.events.size(), Math::addExact);
        }
        if (!queuedEventsByOwner.equals(actual)) {
            logger.warn("Reconciling per-player queued-event counts");
            queuedEventsByOwner.clear();
            queuedEventsByOwner.putAll(actual);
        }
    }

    private static boolean isRunningExecution(CobbleputerBlockEntity blockEntity) {
        return blockEntity.hasExecution()
                && blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) == CobbleputerState.RUNNING;
    }

    private static boolean isChunkLoadResumeCandidate(CobbleputerBlockEntity blockEntity) {
        return blockEntity.hasExecution() && blockEntity.shouldResumeAfterChunkLoad();
    }

    private static String dimensionKey(CobbleputerBlockEntity blockEntity) {
        return blockEntity.getLevel() instanceof ServerLevel level
                ? level.dimension().identifier().toString()
                : "";
    }

    private void drainObserverChanges(Minecraft minecraft) {
        if (pendingObserverChanges.isEmpty()) {
            return;
        }
        List<WirelessObserverBlockEntity> observers = List.copyOf(pendingObserverChanges);
        pendingObserverChanges.clear();
        for (WirelessObserverBlockEntity observer : observers) {
            if (observer.isRemoved() || !minecraft.isServerLevel(observer)) {
                continue;
            }
            if (!minecraft.isLoaded(observer, observer.observedBlockPos())) {
                pendingObserverChanges.add(observer);
                continue;
            }
            minecraft
                    .takeObservedStateChange(observer)
                    .ifPresent(change -> deliverBlockStateChange(minecraft, observer, change));
        }
    }

    private void drainObserverInitializations(Minecraft minecraft) {
        if (pendingObserverInitializations.isEmpty()) {
            return;
        }
        List<WirelessObserverBlockEntity> observers = List.copyOf(pendingObserverInitializations);
        pendingObserverInitializations.clear();
        for (WirelessObserverBlockEntity observer : observers) {
            if (observer.isRemoved() || !minecraft.isServerLevel(observer)) {
                continue;
            }
            if (!minecraft.initializeObservedState(observer)) {
                pendingObserverInitializations.add(observer);
            }
        }
    }

    private void deliverBlockStateChange(
            Minecraft minecraft,
            WirelessObserverBlockEntity observer,
            WirelessObserverBlockEntity.ObservedBlockStateChange change) {
        Optional<net.minecraft.core.GlobalPos> pairedTarget = observer.pairedCobbleputer();
        if (pairedTarget.isEmpty()) {
            return;
        }
        net.minecraft.core.GlobalPos target = pairedTarget.orElseThrow();
        Optional<CobbleputerBlockEntity> targetCobbleputer = minecraft.loadedCobbleputer(target);
        if (targetCobbleputer.isEmpty()) {
            return;
        }
        BlockState observerState = observer.getBlockState();
        Optional<DyeColor> observerColor = minecraft.observerColor(observer);
        if (observerColor.isEmpty()) {
            return;
        }
        Optional<net.minecraft.core.GlobalPos> observerPosition = minecraft.globalPos(observer, observer.getBlockPos());
        Optional<net.minecraft.core.GlobalPos> observedPosition = minecraft.globalPos(observer, change.pos());
        if (observerPosition.isEmpty() || observedPosition.isEmpty()) {
            return;
        }
        BlockStateChange event = BlockStateChange.newBuilder()
                .setObserverColor(protoDyeColor(observerColor.orElseThrow()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observerState.getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerPosition.orElseThrow()))
                .setBlockPos(protoGlobalPos(observedPosition.orElseThrow()))
                .setOldState(protoBlockInfo(change.oldState()))
                .setNewState(protoBlockInfo(change.newState()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setBlockStateChange(event).build());
    }

    private void deliverObserverPaired(
            Minecraft minecraft, WirelessObserverBlockEntity observer, net.minecraft.core.GlobalPos target) {
        Optional<CobbleputerBlockEntity> targetCobbleputer = minecraft.loadedCobbleputer(target);
        if (targetCobbleputer.isEmpty()
                || !(observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
            return;
        }
        Optional<net.minecraft.core.GlobalPos> observerPosition = minecraft.globalPos(observer, observer.getBlockPos());
        if (observerPosition.isEmpty()) {
            return;
        }
        WirelessObserverPaired event = WirelessObserverPaired.newBuilder()
                .setObserverColor(protoDyeColor(wirelessObserver.color()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observer.getBlockState().getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerPosition.orElseThrow()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setWirelessObserverPaired(event).build());
    }

    private void deliverObserverUnpaired(
            Minecraft minecraft, WirelessObserverBlockEntity observer, net.minecraft.core.GlobalPos target) {
        Optional<CobbleputerBlockEntity> targetCobbleputer = minecraft.loadedCobbleputer(target);
        BlockState observerState = observer.getBlockState();
        if (targetCobbleputer.isEmpty()
                || !(observerState.getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
            return;
        }
        Optional<net.minecraft.core.GlobalPos> observerPosition = minecraft.globalPos(observer, observer.getBlockPos());
        if (observerPosition.isEmpty()) {
            return;
        }
        WirelessObserverUnpaired event = WirelessObserverUnpaired.newBuilder()
                .setObserverColor(protoDyeColor(wirelessObserver.color()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observerState.getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerPosition.orElseThrow()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setWirelessObserverUnpaired(event).build());
    }

    private Flight buildFlight() {
        Map<UUID, PlayerFlight> playersByOwner = new LinkedHashMap<>();
        Map<String, OfferedExecution> offered = new HashMap<>();
        List<OfferedExecution> orderedOffered = new ArrayList<>();
        for (Map.Entry<CobbleputerBlockEntity, EphemeralCobbleputerState> entry : loadedCobbleputers.entrySet()) {
            CobbleputerBlockEntity blockEntity = entry.getKey();
            if (!isRunningExecution(blockEntity)) {
                continue;
            }
            UUID executionId = blockEntity.executionId().orElseThrow();
            UUID ownerId = blockEntity.ownerPlayerId().orElseThrow();
            if (executionPoolManager.poolFor(ownerId).isEmpty()) {
                continue;
            }
            byte[] executionState = blockEntity.executionState().orElseThrow();
            List<Event> events = new ArrayList<>();
            for (QueuedEvent queued : entry.getValue().events) {
                events.add(queued.event);
            }
            long firstEventSequence = entry.getValue().events.isEmpty()
                    ? blockEntity.nextEventSequenceNum()
                    : entry.getValue().events.getFirst().sequenceNum;
            PlayerFlight playerFlight = playersByOwner.computeIfAbsent(ownerId, ignored -> new PlayerFlight());
            playerFlight.executions.add(ExecutionState.newBuilder()
                    .setExecutionId(protoUuid(executionId))
                    .setInitialState(com.google.protobuf.ByteString.copyFrom(executionState))
                    .addAllEvents(events)
                    .setEventSeqNum(firstEventSequence)
                    .setStateSizeLimit(stateSizeLimit(ownerId, blockEntity.cobbleputerId()))
                    .setOutboxLimit(OUTBOX_LIMIT)
                    .build());
            OfferedExecution offeredExecution = new OfferedExecution(blockEntity, firstEventSequence, events.size());
            offered.put(uuidKey(executionId), offeredExecution);
            playerFlight.offered.add(offeredExecution);
        }
        List<PlayerExecutionState> players = new ArrayList<>();
        for (Map.Entry<UUID, PlayerFlight> entry : playersByOwner.entrySet()) {
            PlayerFlight playerFlight = entry.getValue();
            players.add(PlayerExecutionState.newBuilder()
                    .setPlayerId(protoUuid(entry.getKey()))
                    .addAllExecutions(playerFlight.executions)
                    .setOutstandingEffectLimit(maxPendingEffectsPerPlayer)
                    .build());
            orderedOffered.addAll(playerFlight.offered);
        }
        return new Flight(
                ExecutionsRequest.newBuilder()
                        .addAllPlayers(players)
                        .setWorkerLimit(Math.addExact(
                                executionPoolManager.guaranteedPoolSize(), executionPoolManager.bestEffortPoolSize()))
                        .build(),
                offered,
                orderedOffered);
    }

    private void applyResponse(Minecraft minecraft, ExecutionsResponse response, Flight completedFlight) {
        if (completedFlight == null || !hasExpectedResults(response, completedFlight)) {
            logger.warn("Discarding invalid execution-sync response");
            return;
        }
        for (int index = 0; index < response.getResultsCount(); index += 1) {
            ExecutionResult result = response.getResults(index);
            OfferedExecution offered = completedFlight.orderedOffered.get(index);
            if (loadedCobbleputers.get(offered.blockEntity) == null
                    || !sameExecutionId(offered.blockEntity, result.getExecutionId())
                    || !isRunningExecution(offered.blockEntity)) {
                continue;
            }
            applyResult(minecraft, offered, result);
        }
    }

    private boolean hasExpectedResults(ExecutionsResponse response, Flight completedFlight) {
        if (response.getResultsCount() != completedFlight.orderedOffered.size()) {
            return false;
        }
        for (int index = 0; index < response.getResultsCount(); index += 1) {
            if (!sameExecutionId(
                    completedFlight.orderedOffered.get(index).blockEntity,
                    response.getResults(index).getExecutionId())) {
                return false;
            }
        }
        return true;
    }

    private void applyResult(Minecraft minecraft, OfferedExecution offered, ExecutionResult result) {
        long minimumConsumed = offered.firstEventSequence - 1;
        long maximumConsumed = offered.firstEventSequence + offered.offeredEventCount - 1;
        if (result.getEventSeqNum() < minimumConsumed || result.getEventSeqNum() > maximumConsumed) {
            logger.warn(
                    "Discarding execution result with invalid sequence numbers at {}",
                    offered.blockEntity.getBlockPos().toShortString());
            return;
        }

        EphemeralCobbleputerState active = loadedCobbleputers.get(offered.blockEntity);
        removeConsumedEvents(offered.blockEntity, active, result.getEventSeqNum());
        applyEffects(minecraft, offered.blockEntity, result);
        if (result.getExecutionFailed()) {
            UUID owner = offered.blockEntity.ownerPlayerId().orElseThrow();
            UUID executionId = offered.blockEntity.executionId().orElseThrow();
            discardPendingEvents(offered.blockEntity, active);
            if (result.getFailureReason() == ExecutionFailureReason.EXECUTION_FAILURE_REASON_STATE_SIZE_LIMIT) {
                mainWorker.reportLimitExceeded(owner, executionId, "TotalExecutionStateSize");
            }
            offered.blockEntity.crashExecution();
            updateVisibleState(minecraft, offered.blockEntity, CobbleputerState.CRASHED);
            reportExecutionTransition(owner, executionId, ExecutionTransition.EXECUTION_TRANSITION_CRASHED);
            return;
        }
        byte[] finalState = result.getFinalState().toByteArray();
        UUID owner = offered.blockEntity.ownerPlayerId().orElseThrow();
        UUID executionId = offered.blockEntity.executionId().orElseThrow();
        if (!fitsStateUpdate(owner, offered.blockEntity.cobbleputerId(), finalState)) {
            discardPendingEvents(offered.blockEntity, active);
            mainWorker.reportLimitExceeded(owner, executionId, "TotalExecutionStateSize");
            offered.blockEntity.crashExecution();
            updateVisibleState(minecraft, offered.blockEntity, CobbleputerState.CRASHED);
            reportExecutionTransition(owner, executionId, ExecutionTransition.EXECUTION_TRANSITION_CRASHED);
            return;
        }
        offered.blockEntity.updateExecutionState(finalState);
    }

    private void applyEffects(Minecraft minecraft, CobbleputerBlockEntity blockEntity, ExecutionResult result) {
        for (Effect effect : result.getEffectsList()) {
            boolean failed = false;
            long startedAt = System.nanoTime();
            try {
                applyEffect(minecraft, blockEntity, effect);
            } catch (RuntimeException exception) {
                failed = true;
                logger.warn(
                        "Could not apply Cobbleputer effect at {}: {}",
                        blockEntity.getBlockPos().toShortString(),
                        exception.toString());
            } finally {
                recordEffect(minecraft, blockEntity, effect, System.nanoTime() - startedAt, failed);
            }
        }
    }

    private void applyEffect(Minecraft minecraft, CobbleputerBlockEntity blockEntity, Effect effect) {
        String message =
                switch (effect.getEffectCase()) {
                    case SET_REDSTONE_POWER -> {
                        applyRedstonePower(minecraft, blockEntity, effect.getSetRedstonePower());
                        yield null;
                    }
                    case EMIT_BEEP -> {
                        if (Integer.compareUnsigned(effect.getEmitBeep().getMillis(), 65535) > 0) {
                            logger.warn(
                                    "Ignoring EmitBeep with an invalid millis value at {}",
                                    blockEntity.getBlockPos().toShortString());
                            yield null;
                        }
                        yield "beep";
                    }
                    case SEND_CHAT -> effect.getSendChat().getMsg();
                    case EFFECT_NOT_SET -> null;
                };
        if (effect.getEffectCase() == Effect.EffectCase.SET_REDSTONE_POWER) {
            return;
        }
        if (message == null) {
            logger.warn(
                    "Ignoring unsupported or unset Cobbleputer effect at {}",
                    blockEntity.getBlockPos().toShortString());
            return;
        }
        minecraft.sendNearbySystemMessage(blockEntity, Component.literal(message));
    }

    private static void applyRedstonePower(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity, cobbleware.effects.SetRedstonePower effect) {
        Optional<UUID> observerId = effect.hasObserverId()
                ? Optional.of(new UUID(
                        effect.getObserverId().getHigh(), effect.getObserverId().getLow()))
                : Optional.empty();
        Optional<DyeColor> observerColor =
                effect.hasObserverColor() ? minecraftDyeColor(effect.getObserverColor()) : Optional.empty();
        if (effect.hasObserverColor() && observerColor.isEmpty()) {
            return;
        }
        minecraft.setRedstonePower(blockEntity, observerId, observerColor, effect.getPowerLevel());
    }

    private static void updateVisibleState(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity, CobbleputerState nextState) {
        minecraft.setCobbleputerState(blockEntity, nextState);
    }

    private static boolean sameExecutionId(CobbleputerBlockEntity blockEntity, Uuid resultId) {
        Optional<UUID> executionId = blockEntity.executionId();
        return executionId.isPresent()
                && executionId.get().getMostSignificantBits() == resultId.getHigh()
                && executionId.get().getLeastSignificantBits() == resultId.getLow();
    }

    private static Uuid protoUuid(UUID uuid) {
        return Uuid.newBuilder()
                .setHigh(uuid.getMostSignificantBits())
                .setLow(uuid.getLeastSignificantBits())
                .build();
    }

    static BlockInfo protoBlockInfo(BlockState state) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyValue(state, property));
        }
        return BlockInfo.newBuilder()
                .setBlockType(cobbleware.common.Identifier.newBuilder()
                        .setNs(blockId.getNamespace())
                        .setPath(blockId.getPath()))
                .putAllProperties(properties)
                .build();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static cobbleware.common.GlobalPos protoGlobalPos(net.minecraft.core.GlobalPos globalPos) {
        Identifier dimension = globalPos.dimension().identifier();
        return cobbleware.common.GlobalPos.newBuilder()
                .setDimension(cobbleware.common.Identifier.newBuilder()
                        .setNs(dimension.getNamespace())
                        .setPath(dimension.getPath()))
                .setPosition(cobbleware.common.Vec3i.newBuilder()
                        .setX(globalPos.pos().getX())
                        .setY(globalPos.pos().getY())
                        .setZ(globalPos.pos().getZ()))
                .build();
    }

    private static cobbleware.common.DyeColor protoDyeColor(DyeColor color) {
        return switch (color) {
            case WHITE -> cobbleware.common.DyeColor.DYE_COLOR_WHITE;
            case ORANGE -> cobbleware.common.DyeColor.DYE_COLOR_ORANGE;
            case MAGENTA -> cobbleware.common.DyeColor.DYE_COLOR_MAGENTA;
            case LIGHT_BLUE -> cobbleware.common.DyeColor.DYE_COLOR_LIGHT_BLUE;
            case YELLOW -> cobbleware.common.DyeColor.DYE_COLOR_YELLOW;
            case LIME -> cobbleware.common.DyeColor.DYE_COLOR_LIME;
            case PINK -> cobbleware.common.DyeColor.DYE_COLOR_PINK;
            case GRAY -> cobbleware.common.DyeColor.DYE_COLOR_GRAY;
            case LIGHT_GRAY -> cobbleware.common.DyeColor.DYE_COLOR_LIGHT_GRAY;
            case CYAN -> cobbleware.common.DyeColor.DYE_COLOR_CYAN;
            case PURPLE -> cobbleware.common.DyeColor.DYE_COLOR_PURPLE;
            case BLUE -> cobbleware.common.DyeColor.DYE_COLOR_BLUE;
            case BROWN -> cobbleware.common.DyeColor.DYE_COLOR_BROWN;
            case GREEN -> cobbleware.common.DyeColor.DYE_COLOR_GREEN;
            case RED -> cobbleware.common.DyeColor.DYE_COLOR_RED;
            case BLACK -> cobbleware.common.DyeColor.DYE_COLOR_BLACK;
        };
    }

    static Optional<DyeColor> minecraftDyeColor(cobbleware.common.DyeColor color) {
        return switch (color) {
            case DYE_COLOR_WHITE -> Optional.of(DyeColor.WHITE);
            case DYE_COLOR_ORANGE -> Optional.of(DyeColor.ORANGE);
            case DYE_COLOR_MAGENTA -> Optional.of(DyeColor.MAGENTA);
            case DYE_COLOR_LIGHT_BLUE -> Optional.of(DyeColor.LIGHT_BLUE);
            case DYE_COLOR_YELLOW -> Optional.of(DyeColor.YELLOW);
            case DYE_COLOR_LIME -> Optional.of(DyeColor.LIME);
            case DYE_COLOR_PINK -> Optional.of(DyeColor.PINK);
            case DYE_COLOR_GRAY -> Optional.of(DyeColor.GRAY);
            case DYE_COLOR_LIGHT_GRAY -> Optional.of(DyeColor.LIGHT_GRAY);
            case DYE_COLOR_CYAN -> Optional.of(DyeColor.CYAN);
            case DYE_COLOR_PURPLE -> Optional.of(DyeColor.PURPLE);
            case DYE_COLOR_BLUE -> Optional.of(DyeColor.BLUE);
            case DYE_COLOR_BROWN -> Optional.of(DyeColor.BROWN);
            case DYE_COLOR_GREEN -> Optional.of(DyeColor.GREEN);
            case DYE_COLOR_RED -> Optional.of(DyeColor.RED);
            case DYE_COLOR_BLACK -> Optional.of(DyeColor.BLACK);
            case DYE_COLOR_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }

    private static cobbleware.common.Direction protoDirection(Direction direction) {
        return switch (direction) {
            case DOWN -> cobbleware.common.Direction.DIRECTION_DOWN;
            case UP -> cobbleware.common.Direction.DIRECTION_UP;
            case NORTH -> cobbleware.common.Direction.DIRECTION_NORTH;
            case SOUTH -> cobbleware.common.Direction.DIRECTION_SOUTH;
            case WEST -> cobbleware.common.Direction.DIRECTION_WEST;
            case EAST -> cobbleware.common.Direction.DIRECTION_EAST;
        };
    }

    private static String uuidKey(UUID uuid) {
        return uuid.getMostSignificantBits() + ":" + uuid.getLeastSignificantBits();
    }

    private static String uuidKey(Uuid uuid) {
        return uuid.getHigh() + ":" + uuid.getLow();
    }

    private void recordEvent(CobbleputerBlockEntity blockEntity, Event event, boolean accepted) {
        instrumentationKey(blockEntity)
                .ifPresent(target -> instrumentation.recordEvent(target, event.getEventCase(), accepted));
    }

    private void recordEventsPerSync(Minecraft minecraft, Flight dispatchedFlight) {
        for (OfferedExecution offered : dispatchedFlight.offered.values()) {
            instrumentationKey(minecraft, offered.blockEntity)
                    .ifPresent(target -> instrumentation.recordEventsPerSync(target, offered.offeredEventCount));
        }
    }

    /**
     * Records a rejected event unless it was ignored because the Cobbleputer is stopped.
     * Stopped executions retain their existing queue, but do not admit new events or count them as dropped.
     */
    private void recordRejectedEvent(CobbleputerBlockEntity blockEntity, Event event) {
        CobbleputerState lifecycleState = blockEntity.getBlockState().getValue(CobbleputerBlock.STATE);
        if (countsRejectedEventAsDropped(lifecycleState)) {
            recordEvent(blockEntity, event, false);
        }
    }

    static boolean countsRejectedEventAsDropped(CobbleputerState lifecycleState) {
        return lifecycleState != CobbleputerState.STOPPED;
    }

    private void recordEffect(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity, Effect effect, long elapsedNanos, boolean failed) {
        instrumentationKey(minecraft, blockEntity)
                .ifPresent(
                        target -> instrumentation.recordEffect(target, effect.getEffectCase(), elapsedNanos, failed));
    }

    private static Optional<Instrumentation.PlayerCobbleputerKey> instrumentationKey(
            Minecraft minecraft, CobbleputerBlockEntity blockEntity) {
        Optional<net.minecraft.core.GlobalPos> globalPos = minecraft.globalPos(blockEntity, blockEntity.getBlockPos());
        if (globalPos.isEmpty()) {
            return Optional.empty();
        }
        return instrumentationKey(
                blockEntity, globalPos.orElseThrow().dimension().identifier());
    }

    private static Optional<Instrumentation.PlayerCobbleputerKey> instrumentationKey(
            CobbleputerBlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return instrumentationKey(blockEntity, level.dimension().identifier());
    }

    private static Optional<Instrumentation.PlayerCobbleputerKey> instrumentationKey(
            CobbleputerBlockEntity blockEntity, Identifier dimension) {
        Optional<UUID> owner = blockEntity.ownerPlayerId();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        BlockPos pos = blockEntity.getBlockPos();
        return Optional.of(new Instrumentation.PlayerCobbleputerKey(
                owner.orElseThrow(),
                ownerName(blockEntity, owner.orElseThrow()),
                new Instrumentation.CobbleputerKey(
                        blockEntity.cobbleputerId(), dimension.toString(), pos.getX(), pos.getY(), pos.getZ())));
    }

    private void recordCobbleputerStateGauge(Minecraft minecraft) {
        Map<Instrumentation.PlayerCobbleputerKey, Instrumentation.CobbleputerStateMeasurement> states = new HashMap<>();
        for (CobbleputerBlockEntity blockEntity : loadedCobbleputers.keySet()) {
            Instrumentation.LifecycleState lifecycle =
                    switch (blockEntity.getBlockState().getValue(CobbleputerBlock.STATE)) {
                        case RUNNING -> Instrumentation.LifecycleState.RUNNING;
                        case STOPPED -> Instrumentation.LifecycleState.STOPPED;
                        case UNINITIALIZED, CRASHED -> null;
                    };
            if (lifecycle == null) {
                continue;
            }
            long stateSize = blockEntity
                    .executionState()
                    .map(state -> (long) state.length)
                    .orElse(0L);
            instrumentationKey(minecraft, blockEntity)
                    .ifPresent(key ->
                            states.put(key, new Instrumentation.CobbleputerStateMeasurement(lifecycle, stateSize)));
        }
        instrumentation.recordCobbleputerStates(states);
    }

    private void recordCobbleputerStateGaugeAndStream(Minecraft minecraft) {
        recordCobbleputerStateGauge(minecraft);
        mainWorker.reportExecutionStats(ExecutionStatsReporter.report(
                instrumentation.currentTickSnapshot(),
                System.currentTimeMillis(),
                executionStateAccess.stateSizeCensus()));
    }

    private void reportExecutionTransition(CobbleputerBlockEntity blockEntity, ExecutionTransition transition) {
        reportExecutionTransition(
                blockEntity.ownerPlayerId().orElseThrow(),
                blockEntity.executionId().orElseThrow(),
                transition);
    }

    private void reportExecutionTransition(UUID owner, UUID executionId, ExecutionTransition transition) {
        mainWorker.reportExecutionTransition(
                owner,
                executionId,
                transition,
                runningExecutionCount(owner, Collections.emptySet()),
                maxRunningExecutionsPerPlayer,
                maxTotalExecutionStateBytesPerPlayer);
    }

    private void reportExecutionPoolTransitions(Set<UUID> eligiblePlayerIds) {
        Map<UUID, ExecutionPoolTransition> next = new HashMap<>();
        for (UUID playerId : eligiblePlayerIds) {
            next.put(playerId, executionPoolTransition(playerId));
        }

        List<UUID> departed = new ArrayList<>();
        for (UUID playerId : publishedPoolTransitions.keySet()) {
            if (!next.containsKey(playerId)) {
                departed.add(playerId);
            }
        }
        departed.sort(UUID::compareTo);
        for (UUID playerId : departed) {
            mainWorker.reportExecutionPoolTransition(playerId, ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_NONE);
        }

        List<UUID> playerIds = new ArrayList<>(next.keySet());
        playerIds.sort(UUID::compareTo);
        for (UUID playerId : playerIds) {
            ExecutionPoolTransition transition = next.get(playerId);
            if (transition != publishedPoolTransitions.get(playerId)) {
                mainWorker.reportExecutionPoolTransition(playerId, transition);
            }
        }
        publishedPoolTransitions = Map.copyOf(next);
    }

    private ExecutionPoolTransition executionPoolTransition(UUID playerId) {
        Optional<ExecutionPoolManager.Pool> pool = executionPoolManager.poolFor(playerId);
        if (pool.isEmpty()) {
            return ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_WAITING;
        }
        return switch (pool.orElseThrow()) {
            case GUARANTEED -> ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_GUARANTEED;
            case BEST_EFFORT -> ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_BESTEFFORT;
        };
    }

    private static String ownerName(CobbleputerBlockEntity blockEntity, UUID ownerId) {
        return blockEntity.ownerPlayerName().filter(name -> !name.isEmpty()).orElse(ownerId.toString());
    }

    private static Instrumentation.SyncOutcome syncOutcome(MainWorker.ExecutionSyncResult result, Throwable failure) {
        if (failure != null || result == null) {
            return Instrumentation.SyncOutcome.EXCEPTION;
        }
        return switch (result.status()) {
            case SUCCESS -> Instrumentation.SyncOutcome.SUCCESS;
            case CONFIGURATION_ERROR -> Instrumentation.SyncOutcome.CONFIGURATION_ERROR;
            case UNAUTHORIZED -> Instrumentation.SyncOutcome.UNAUTHORIZED;
            case UNAVAILABLE -> Instrumentation.SyncOutcome.UNAVAILABLE;
        };
    }

    private record QueuedEvent(long sequenceNum, Event event) {}

    private record SyncCompletion(long elapsedNanos, Instrumentation.SyncOutcome outcome) {}

    private static final class EphemeralCobbleputerState {
        private final ArrayDeque<QueuedEvent> events = new ArrayDeque<>();
        private UUID eventOwner;
    }

    private record OfferedExecution(
            CobbleputerBlockEntity blockEntity, long firstEventSequence, int offeredEventCount) {}

    private static final class PlayerFlight {
        private final List<ExecutionState> executions = new ArrayList<>();
        private final List<OfferedExecution> offered = new ArrayList<>();
    }

    private record Flight(
            ExecutionsRequest request,
            Map<String, OfferedExecution> offered,
            List<OfferedExecution> orderedOffered,
            long dispatchNanos) {
        private Flight(
                ExecutionsRequest request,
                Map<String, OfferedExecution> offered,
                List<OfferedExecution> orderedOffered) {
            this(request, offered, orderedOffered, -1);
        }

        private Flight withDispatchNanos(long nextDispatchNanos) {
            return new Flight(request, offered, orderedOffered, nextDispatchNanos);
        }

        private boolean hadExecutions() {
            return !offered.isEmpty();
        }
    }

    private static final class ServerMinecraft implements Minecraft {
        private final MinecraftServer server;

        private ServerMinecraft(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public Optional<CobbleputerBlockEntity> loadedCobbleputer(net.minecraft.core.GlobalPos target) {
            ServerLevel targetLevel = server.getLevel(target.dimension());
            if (targetLevel == null
                    || !targetLevel.isLoaded(target.pos())
                    || !(targetLevel.getBlockEntity(target.pos()) instanceof CobbleputerBlockEntity cobbleputer)) {
                return Optional.empty();
            }
            return Optional.of(cobbleputer);
        }

        @Override
        public boolean isServerLevel(BlockEntity blockEntity) {
            return serverLevel(blockEntity).isPresent();
        }

        @Override
        public OptionalLong gameTime(CobbleputerBlockEntity blockEntity) {
            return serverLevel(blockEntity).isPresent()
                    ? OptionalLong.of(serverLevel(blockEntity).orElseThrow().getGameTime())
                    : OptionalLong.empty();
        }

        @Override
        public Optional<net.minecraft.core.GlobalPos> globalPos(BlockEntity blockEntity, BlockPos pos) {
            return serverLevel(blockEntity).map(level -> net.minecraft.core.GlobalPos.of(level.dimension(), pos));
        }

        @Override
        public boolean isLoaded(BlockEntity blockEntity, BlockPos pos) {
            return serverLevel(blockEntity).map(level -> level.isLoaded(pos)).orElse(false);
        }

        @Override
        public Optional<WirelessObserverBlockEntity.ObservedBlockStateChange> takeObservedStateChange(
                WirelessObserverBlockEntity observer) {
            return isServerLevel(observer) ? observer.takeObservedStateChange() : Optional.empty();
        }

        @Override
        public boolean initializeObservedState(WirelessObserverBlockEntity observer) {
            return isServerLevel(observer) && observer.initializeObservedState();
        }

        @Override
        public Optional<DyeColor> observerColor(WirelessObserverBlockEntity observer) {
            return observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver
                    ? Optional.of(wirelessObserver.color())
                    : Optional.empty();
        }

        @Override
        public boolean setCobbleputerState(CobbleputerBlockEntity blockEntity, CobbleputerState nextState) {
            return serverLevel(blockEntity)
                    .map(level -> CobbleputerBlock.setLifecycleState(
                            level, blockEntity.getBlockPos(), blockEntity.getBlockState(), nextState))
                    .orElse(false);
        }

        @Override
        public void sendSystemMessage(UUID playerId, Component message) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }

        @Override
        public void sendNearbySystemMessage(CobbleputerBlockEntity blockEntity, Component message) {
            Optional<ServerLevel> level = serverLevel(blockEntity);
            if (level.isEmpty()) {
                return;
            }
            Vec3 position = Vec3.atCenterOf(blockEntity.getBlockPos());
            for (ServerPlayer player : level.orElseThrow().players()) {
                if (player.distanceToSqr(position) <= CHAT_RADIUS_SQUARED) {
                    player.sendSystemMessage(message);
                }
            }
        }

        @Override
        public void setRedstonePower(
                CobbleputerBlockEntity blockEntity,
                Optional<UUID> observerId,
                Optional<DyeColor> observerColor,
                int powerLevel) {
            Optional<ServerLevel> level = serverLevel(blockEntity);
            io.github.mmcgill.cobbleware.block.WirelessObserverNetwork network =
                    CobblewareMod.wirelessObserverNetworkOrNull();
            if (level.isEmpty() || network == null) {
                return;
            }
            network.setRedstonePower(
                    net.minecraft.core.GlobalPos.of(level.orElseThrow().dimension(), blockEntity.getBlockPos()),
                    observerId,
                    observerColor,
                    powerLevel);
        }

        private static Optional<ServerLevel> serverLevel(BlockEntity blockEntity) {
            return blockEntity != null && blockEntity.getLevel() instanceof ServerLevel level
                    ? Optional.of(level)
                    : Optional.empty();
        }
    }
}
