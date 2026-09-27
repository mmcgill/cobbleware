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
import cobbleware.internal.ExecutionResult;
import cobbleware.internal.ExecutionState;
import cobbleware.internal.ExecutionStatus;
import cobbleware.internal.ExecutionTransition;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlock;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.instrumentation.ExecutionStatsReporter;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Drives the best-effort synchronization of active Cobbleputer executions. */
public final class ExecutionDriver {
    private static final int MAX_PENDING_EVENTS_PER_EXECUTION = 50;
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

    private final MainWorkerClient mainWorkerClient;
    private final Instrumentation instrumentation;
    private final int maxRunningExecutionsPerPlayer;
    private final Logger logger;
    private final Map<CobbleputerBlockEntity, EphemeralCobbleputerState> loadedCobbleputers = new IdentityHashMap<>();
    private final Set<WirelessObserverBlockEntity> pendingObserverInitializations =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CobbleputerBlockEntity> pendingChunkLoadResumes =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CobbleputerBlockEntity> pendingChunkNonTickingTransitions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<WirelessObserverBlockEntity> pendingObserverChanges =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConcurrentLinkedQueue<SyncCompletion> completedSyncMeasurements = new ConcurrentLinkedQueue<>();

    private CompletableFuture<MainWorkerClient.ExecutionSyncResult> inFlight;
    private Flight flight;
    private boolean runtimeMayHaveExecutions;
    private long instrumentationTick;

    public ExecutionDriver(
            MainWorkerClient mainWorkerClient,
            Instrumentation instrumentation,
            int maxRunningExecutionsPerPlayer,
            Logger logger) {
        if (mainWorkerClient == null
                || instrumentation == null
                || logger == null
                || maxRunningExecutionsPerPlayer <= 0) {
            throw new IllegalArgumentException(
                    "ExecutionDriver dependencies must not be null and running-execution limit must be positive");
        }
        this.mainWorkerClient = mainWorkerClient;
        this.instrumentation = instrumentation;
        this.maxRunningExecutionsPerPlayer = maxRunningExecutionsPerPlayer;
        this.logger = logger;
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, parameters) -> dispatchChatMessage(sender, message.signedContent()));
    }

    /** Adds a loaded Cobbleputer to the driver's loaded set. */
    public void activate(CobbleputerBlockEntity blockEntity) {
        ExecutionStateStore executionStateStore = CobblewareMod.executionStateStoreOrNull();
        if (executionStateStore != null) {
            executionStateStore.attach(blockEntity);
        }
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
        loadedCobbleputers.remove(blockEntity);
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
        loadedCobbleputers.remove(blockEntity);
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

    /** Returns the Mod's read-only in-memory instrumentation collector. */
    public Instrumentation instrumentation() {
        return instrumentation;
    }

    /** Delivers lifecycle events after an observer's durable pairing has been replaced. */
    public void observerPaired(
            WirelessObserverBlockEntity observer,
            net.minecraft.core.GlobalPos previousTarget,
            net.minecraft.core.GlobalPos newTarget) {
        if (observer == null
                || newTarget == null
                || observer.isRemoved()
                || !(observer.getLevel() instanceof ServerLevel observerLevel)) {
            return;
        }
        MinecraftServer server = observerLevel.getServer();
        if (previousTarget != null && !previousTarget.equals(newTarget)) {
            deliverObserverUnpaired(server, observer, observerLevel, previousTarget);
        }
        deliverObserverPaired(server, observer, observerLevel, newTarget);
    }

    /** Delivers the unpair lifecycle event before a paired observer block is discarded. */
    public void observerRemoved(WirelessObserverBlockEntity observer) {
        if (observer == null || observer.isRemoved() || !(observer.getLevel() instanceof ServerLevel observerLevel)) {
            return;
        }
        observer.pairedCobbleputer()
                .ifPresent(
                        target -> deliverObserverUnpaired(observerLevel.getServer(), observer, observerLevel, target));
    }

    /** Starts a fresh execution and queues its first lifecycle event when the owner has capacity. */
    public ExecutionInitResult startExecution(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            CobbleputerBlockEntity blockEntity,
            UUID owner,
            String ownerName,
            byte[] initialState) {
        if (owner == null || ownerName == null || ownerName.isEmpty() || initialState == null) {
            throw new IllegalArgumentException(
                    "execution owner, owner name, and initial state must not be null or empty");
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(owner)) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        loadedCobbleputers.get(blockEntity).events.clear();
        blockEntity.startExecution(owner, ownerName, initialState);
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.RUNNING)) {
            enqueueEvent(
                    blockEntity,
                    Event.newBuilder()
                            .setStarted(Started.newBuilder().setTick(level.getGameTime()))
                            .build());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STARTED);
            return ExecutionInitResult.SUCCESS;
        }
        return ExecutionInitResult.INVALID_STATE;
    }

    /** Stops a running execution without discarding its execution record or queued events. */
    public void stop(ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasExecution() || state.getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            return;
        }
        activate(blockEntity);
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.STOPPED)) {
            blockEntity.setStoppedAtTick(level.getGameTime());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STOPPED);
        }
    }

    /** Resumes a stopped execution and queues its resume lifecycle event when the owner has capacity. */
    public ExecutionInitResult resume(
            ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasExecution() || state.getValue(CobbleputerBlock.STATE) != CobbleputerState.STOPPED) {
            return ExecutionInitResult.INVALID_STATE;
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(blockEntity.ownerPlayerId().orElseThrow())) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        return transitionToRunningAndQueueResumed(level, pos, state, blockEntity);
    }

    /** Restarts a crashed execution with its retained state when the owner has capacity. */
    public ExecutionInitResult restartCrashedExecution(
            ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasRecoverableCrashedExecution()
                || state.getValue(CobbleputerBlock.STATE) != CobbleputerState.CRASHED) {
            return ExecutionInitResult.INVALID_STATE;
        }
        activate(blockEntity);
        if (!hasRunningExecutionCapacity(blockEntity.ownerPlayerId().orElseThrow())) {
            return ExecutionInitResult.LIMIT_REACHED;
        }
        EphemeralCobbleputerState active = loadedCobbleputers.get(blockEntity);
        active.events.clear();
        blockEntity.restartCrashedExecution();
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.RUNNING)) {
            enqueueEvent(
                    blockEntity,
                    Event.newBuilder()
                            .setStarted(Started.newBuilder().setTick(level.getGameTime()))
                            .build());
            reportExecutionTransition(blockEntity, ExecutionTransition.EXECUTION_TRANSITION_STARTED);
            return ExecutionInitResult.SUCCESS;
        }
        return ExecutionInitResult.INVALID_STATE;
    }

    /** Queues an event for a running active Cobbleputer when it can be accepted. */
    public void enqueueEvent(CobbleputerBlockEntity blockEntity, Event event) {
        if (blockEntity == null || event == null) {
            throw new IllegalArgumentException("Cobbleputer event arguments must not be null");
        }
        EphemeralCobbleputerState ephemeralState = loadedCobbleputers.get(blockEntity);
        if (ephemeralState != null) {
            enqueueActiveEvent(blockEntity, ephemeralState, event);
        } else {
            recordRejectedEvent(blockEntity, event);
        }
    }

    /** Delivers raw player chat to every eligible active Cobbleputer in the sender's level and range. */
    private void dispatchChatMessage(ServerPlayer sender, String message) {
        Event event = Event.newBuilder()
                .setChatMessage(ChatMessage.newBuilder().setMessage(message))
                .build();
        Level senderLevel = sender.level();
        for (Map.Entry<CobbleputerBlockEntity, EphemeralCobbleputerState> entry : loadedCobbleputers.entrySet()) {
            CobbleputerBlockEntity blockEntity = entry.getKey();
            if (blockEntity.getLevel() != senderLevel
                    || sender.distanceToSqr(Vec3.atCenterOf(blockEntity.getBlockPos())) > CHAT_RADIUS_SQUARED) {
                continue;
            }
            enqueueActiveEvent(blockEntity, entry.getValue(), event);
        }
    }

    private void enqueueActiveEvent(CobbleputerBlockEntity blockEntity, EphemeralCobbleputerState active, Event event) {
        if (active == null || !blockEntity.hasExecution()) {
            recordRejectedEvent(blockEntity, event);
            return;
        }
        if (blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            recordRejectedEvent(blockEntity, event);
            return;
        }
        if (active.events.size() >= MAX_PENDING_EVENTS_PER_EXECUTION) {
            logger.debug(
                    "Dropping Cobbleputer event at {} because its pending queue is full",
                    blockEntity.getBlockPos().toShortString());
            recordRejectedEvent(blockEntity, event);
            return;
        }
        try {
            active.events.addLast(new QueuedEvent(blockEntity.claimNextEventSequenceNum(), event));
            recordEvent(blockEntity, event, true);
        } catch (RuntimeException exception) {
            logger.error(
                    "Could not enqueue Cobbleputer event at {}: {}",
                    blockEntity.getBlockPos().toShortString(),
                    exception.toString());
            recordRejectedEvent(blockEntity, event);
        }
    }

    private void tick(MinecraftServer server) {
        instrumentationTick += 1;
        instrumentation.advanceTick(instrumentationTick);
        drainChunkNonTickingTransitions();
        drainChunkLoadResumes(server);
        drainCompletedSyncMeasurements();
        if (inFlight != null) {
            if (!inFlight.isDone()) {
                recordCobbleputerStateGaugeAndStream(server);
                return;
            }
            MainWorkerClient.ExecutionSyncResult result;
            try {
                result = inFlight.join();
            } catch (RuntimeException exception) {
                logger.warn("Execution sync completion failed: {}", exception.toString());
                result = MainWorkerClient.ExecutionSyncResult.unavailable("Main Worker request failed");
            }
            inFlight = null;
            if (result.status() == MainWorkerClient.ExecutionSyncStatus.SUCCESS) {
                applyResponse(result.response(), flight);
                if (!flight.hadExecutions()) {
                    runtimeMayHaveExecutions = false;
                }
            } else {
                logger.warn("Execution sync did not succeed ({}): {}", result.status(), result.detail());
            }
            flight = null;
        }

        drainObserverInitializations();
        drainObserverChanges(server);
        Flight nextFlight = buildFlight();
        if (!nextFlight.hadExecutions() && !runtimeMayHaveExecutions) {
            recordCobbleputerStateGaugeAndStream(server);
            return;
        }
        if (nextFlight.hadExecutions()) {
            runtimeMayHaveExecutions = true;
        }
        long dispatchedAt = System.nanoTime();
        Flight dispatchedFlight = nextFlight.withDispatchNanos(dispatchedAt);
        flight = dispatchedFlight;
        recordEventsPerSync(dispatchedFlight);
        inFlight = mainWorkerClient.syncExecutions(nextFlight.request);
        inFlight.whenComplete((result, failure) -> completedSyncMeasurements.add(new SyncCompletion(
                System.nanoTime() - dispatchedFlight.dispatchNanos(), syncOutcome(result, failure))));
        recordCobbleputerStateGaugeAndStream(server);
    }

    private void drainCompletedSyncMeasurements() {
        SyncCompletion completion;
        while ((completion = completedSyncMeasurements.poll()) != null) {
            instrumentation.recordSyncCompletion(completion.elapsedNanos, completion.outcome);
        }
    }

    /** Performs durable state changes deferred from the distance manager's chunk-status callback. */
    private void drainChunkNonTickingTransitions() {
        if (pendingChunkNonTickingTransitions.isEmpty()) {
            return;
        }
        List<CobbleputerBlockEntity> cobbleputers = List.copyOf(pendingChunkNonTickingTransitions);
        pendingChunkNonTickingTransitions.clear();
        for (CobbleputerBlockEntity cobbleputer : cobbleputers) {
            if (cobbleputer.isRemoved() || !(cobbleputer.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            boolean wasRunning = isRunningExecution(cobbleputer);
            cobbleputer.recordChunkUnloadResumeIntent(wasRunning);
            if (wasRunning
                    && CobbleputerBlock.setLifecycleState(
                            level, cobbleputer.getBlockPos(), cobbleputer.getBlockState(), CobbleputerState.STOPPED)) {
                cobbleputer.setStoppedAtTick(level.getGameTime());
                reportExecutionTransition(cobbleputer, ExecutionTransition.EXECUTION_TRANSITION_STOPPED);
            }
        }
    }

    /**
     * Admits executions that were stopped when their chunks fell below block-ticking status and are now eligible to resume.
     * This deliberately runs on the normal server tick rather than {@link #activate}, because both changing
     * lifecycle state and consuming durable resume intent call {@code setChanged}.
     */
    private void drainChunkLoadResumes(MinecraftServer server) {
        if (pendingChunkLoadResumes.isEmpty()) {
            return;
        }

        List<CobbleputerBlockEntity> candidates = new ArrayList<>();
        for (CobbleputerBlockEntity blockEntity : pendingChunkLoadResumes) {
            if (loadedCobbleputers.containsKey(blockEntity)
                    && isChunkLoadResumeCandidate(blockEntity)
                    && blockEntity.getLevel() instanceof ServerLevel) {
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
                if (remainingCapacity > 0 && resumeAfterChunkLoad(candidate) == ExecutionInitResult.SUCCESS) {
                    remainingCapacity -= 1;
                    continue;
                }
                rejectChunkLoadResumeForRunningExecutionLimit(server, candidate, entry.getKey());
            }
        }
    }

    private ExecutionInitResult resumeAfterChunkLoad(CobbleputerBlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)
                || !blockEntity.hasExecution()
                || !blockEntity.shouldResumeAfterChunkLoad()) {
            return ExecutionInitResult.INVALID_STATE;
        }
        CobbleputerState state = blockEntity.getBlockState().getValue(CobbleputerBlock.STATE);
        if (state != CobbleputerState.STOPPED && state != CobbleputerState.RUNNING) {
            return ExecutionInitResult.INVALID_STATE;
        }
        return transitionToRunningAndQueueResumed(
                level, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity);
    }

    private ExecutionInitResult transitionToRunningAndQueueResumed(
            ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        CobbleputerState lifecycleState = state.getValue(CobbleputerBlock.STATE);
        if (lifecycleState == CobbleputerState.STOPPED) {
            if (!CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.RUNNING)) {
                return ExecutionInitResult.INVALID_STATE;
            }
        } else if (lifecycleState != CobbleputerState.RUNNING) {
            return ExecutionInitResult.INVALID_STATE;
        }
        long resumeTick = level.getGameTime();
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
            MinecraftServer server, CobbleputerBlockEntity blockEntity, UUID owner) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || !blockEntity.shouldResumeAfterChunkLoad()) {
            return;
        }
        if (isRunningExecution(blockEntity)) {
            stop(level, blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity);
        }
        if (blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.STOPPED) {
            return;
        }
        blockEntity.takeChunkUnloadResumeIntent();
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(
                    Component.literal("Paused your Cobbleputer execution: the running-executions-per-player limit is "
                            + maxRunningExecutionsPerPlayer + "."));
        }
    }

    /** Returns the configured maximum number of loaded running executions for one player. */
    public int maxRunningExecutionsPerPlayer() {
        return maxRunningExecutionsPerPlayer;
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

    private void drainObserverChanges(MinecraftServer server) {
        if (pendingObserverChanges.isEmpty()) {
            return;
        }
        List<WirelessObserverBlockEntity> observers = List.copyOf(pendingObserverChanges);
        pendingObserverChanges.clear();
        for (WirelessObserverBlockEntity observer : observers) {
            if (observer.isRemoved() || !(observer.getLevel() instanceof ServerLevel observerLevel)) {
                continue;
            }
            if (!observerLevel.isLoaded(observer.observedBlockPos())) {
                pendingObserverChanges.add(observer);
                continue;
            }
            observer.takeObservedStateChange()
                    .ifPresent(change -> deliverBlockStateChange(server, observer, observerLevel, change));
        }
    }

    private void drainObserverInitializations() {
        if (pendingObserverInitializations.isEmpty()) {
            return;
        }
        List<WirelessObserverBlockEntity> observers = List.copyOf(pendingObserverInitializations);
        pendingObserverInitializations.clear();
        for (WirelessObserverBlockEntity observer : observers) {
            if (observer.isRemoved() || !(observer.getLevel() instanceof ServerLevel)) {
                continue;
            }
            if (!observer.initializeObservedState()) {
                pendingObserverInitializations.add(observer);
            }
        }
    }

    private void deliverBlockStateChange(
            MinecraftServer server,
            WirelessObserverBlockEntity observer,
            ServerLevel observerLevel,
            WirelessObserverBlockEntity.ObservedBlockStateChange change) {
        Optional<net.minecraft.core.GlobalPos> pairedTarget = observer.pairedCobbleputer();
        if (pairedTarget.isEmpty()) {
            return;
        }
        net.minecraft.core.GlobalPos target = pairedTarget.orElseThrow();
        Optional<CobbleputerBlockEntity> targetCobbleputer = resolveCobbleputer(server, target);
        if (targetCobbleputer.isEmpty()) {
            return;
        }
        BlockState observerState = observer.getBlockState();
        if (!(observerState.getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
            return;
        }
        BlockStateChange event = BlockStateChange.newBuilder()
                .setObserverColor(protoDyeColor(wirelessObserver.color()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observerState.getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerLevel, observer.getBlockPos()))
                .setBlockPos(protoGlobalPos(observerLevel, change.pos()))
                .setOldState(protoBlockInfo(change.oldState()))
                .setNewState(protoBlockInfo(change.newState()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setBlockStateChange(event).build());
    }

    private void deliverObserverPaired(
            MinecraftServer server,
            WirelessObserverBlockEntity observer,
            ServerLevel observerLevel,
            net.minecraft.core.GlobalPos target) {
        Optional<CobbleputerBlockEntity> targetCobbleputer = resolveCobbleputer(server, target);
        if (targetCobbleputer.isEmpty()
                || !(observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
            return;
        }
        WirelessObserverPaired event = WirelessObserverPaired.newBuilder()
                .setObserverColor(protoDyeColor(wirelessObserver.color()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observer.getBlockState().getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerLevel, observer.getBlockPos()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setWirelessObserverPaired(event).build());
    }

    private void deliverObserverUnpaired(
            MinecraftServer server,
            WirelessObserverBlockEntity observer,
            ServerLevel observerLevel,
            net.minecraft.core.GlobalPos target) {
        Optional<CobbleputerBlockEntity> targetCobbleputer = resolveCobbleputer(server, target);
        BlockState observerState = observer.getBlockState();
        if (targetCobbleputer.isEmpty()
                || !(observerState.getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
            return;
        }
        WirelessObserverUnpaired event = WirelessObserverUnpaired.newBuilder()
                .setObserverColor(protoDyeColor(wirelessObserver.color()))
                .setObserverId(protoUuid(observer.observerId()))
                .setObserverDir(protoDirection(observerState.getValue(DirectionalBlock.FACING)))
                .setObserverPos(protoGlobalPos(observerLevel, observer.getBlockPos()))
                .build();
        enqueueEvent(
                targetCobbleputer.orElseThrow(),
                Event.newBuilder().setWirelessObserverUnpaired(event).build());
    }

    private static Optional<CobbleputerBlockEntity> resolveCobbleputer(
            MinecraftServer server, net.minecraft.core.GlobalPos target) {
        ServerLevel targetLevel = server.getLevel(target.dimension());
        if (targetLevel == null
                || !targetLevel.isLoaded(target.pos())
                || !(targetLevel.getBlockEntity(target.pos()) instanceof CobbleputerBlockEntity cobbleputer)) {
            return Optional.empty();
        }
        return Optional.of(cobbleputer);
    }

    private Flight buildFlight() {
        List<ExecutionState> executions = new ArrayList<>();
        Map<String, OfferedExecution> offered = new HashMap<>();
        for (Map.Entry<CobbleputerBlockEntity, EphemeralCobbleputerState> entry : loadedCobbleputers.entrySet()) {
            CobbleputerBlockEntity blockEntity = entry.getKey();
            if (!blockEntity.hasExecution()) {
                continue;
            }
            ExecutionStatus status = executionStatus(blockEntity.getBlockState());
            if (status == null) {
                continue;
            }
            UUID executionId = blockEntity.executionId().orElseThrow();
            UUID ownerId = blockEntity.ownerPlayerId().orElseThrow();
            byte[] executionState = blockEntity.executionState().orElseThrow();
            List<Event> events = new ArrayList<>();
            for (QueuedEvent queued : entry.getValue().events) {
                if (events.size() == MAX_PENDING_EVENTS_PER_EXECUTION) {
                    break;
                }
                events.add(queued.event);
            }
            long firstEventSequence = entry.getValue().events.isEmpty()
                    ? blockEntity.nextEventSequenceNum()
                    : entry.getValue().events.getFirst().sequenceNum;
            executions.add(ExecutionState.newBuilder()
                    .setExecutionId(protoUuid(executionId))
                    .setPlayerId(protoUuid(ownerId))
                    .setInitialState(com.google.protobuf.ByteString.copyFrom(executionState))
                    .addAllEvents(events)
                    .setEventSeqNum(firstEventSequence)
                    .setEffectSeqNum(blockEntity.effectSequenceNum())
                    .setState(status)
                    .build());
            offered.put(
                    uuidKey(executionId),
                    new OfferedExecution(
                            blockEntity, firstEventSequence, events.size(), blockEntity.effectSequenceNum(), status));
        }
        return new Flight(
                ExecutionsRequest.newBuilder().addAllExecutions(executions).build(), offered);
    }

    private void applyResponse(ExecutionsResponse response, Flight completedFlight) {
        if (completedFlight == null || !hasExpectedResults(response, completedFlight)) {
            logger.warn("Discarding invalid execution-sync response");
            return;
        }
        for (ExecutionResult result : response.getResultsList()) {
            OfferedExecution offered = completedFlight.offered.get(uuidKey(result.getExecutionId()));
            if (offered == null
                    || loadedCobbleputers.get(offered.blockEntity) == null
                    || !sameExecutionId(offered.blockEntity, result.getExecutionId())
                    || executionStatus(offered.blockEntity.getBlockState()) != offered.lifecycleState) {
                continue;
            }
            applyResult(offered, result);
        }
    }

    private boolean hasExpectedResults(ExecutionsResponse response, Flight completedFlight) {
        if (response.getResultsCount() != completedFlight.offered.size()) {
            return false;
        }
        Map<String, Boolean> seen = new HashMap<>();
        for (ExecutionResult result : response.getResultsList()) {
            String key = uuidKey(result.getExecutionId());
            if (!completedFlight.offered.containsKey(key) || seen.put(key, Boolean.TRUE) != null) {
                return false;
            }
        }
        return true;
    }

    private void applyResult(OfferedExecution offered, ExecutionResult result) {
        ExecutionStatus status = result.getState();
        if (status != ExecutionStatus.EXECUTION_STATUS_RUNNING
                && status != ExecutionStatus.EXECUTION_STATUS_STOPPED
                && status != ExecutionStatus.EXECUTION_STATUS_CRASHED) {
            logger.warn(
                    "Discarding execution result with invalid lifecycle state at {}",
                    offered.blockEntity.getBlockPos().toShortString());
            return;
        }
        long minimumConsumed = offered.firstEventSequence - 1;
        long maximumConsumed = offered.firstEventSequence + offered.offeredEventCount - 1;
        if (result.getEventSeqNum() < minimumConsumed
                || result.getEventSeqNum() > maximumConsumed
                || result.getEffectSeqNum() != offered.effectSequenceNum + 1) {
            logger.warn(
                    "Discarding execution result with invalid sequence numbers at {}",
                    offered.blockEntity.getBlockPos().toShortString());
            return;
        }

        EphemeralCobbleputerState active = loadedCobbleputers.get(offered.blockEntity);
        while (!active.events.isEmpty() && active.events.getFirst().sequenceNum <= result.getEventSeqNum()) {
            active.events.removeFirst();
        }
        applyEffects(offered.blockEntity, result);
        if (status == ExecutionStatus.EXECUTION_STATUS_CRASHED) {
            active.events.clear();
            UUID owner = offered.blockEntity.ownerPlayerId().orElseThrow();
            UUID executionId = offered.blockEntity.executionId().orElseThrow();
            offered.blockEntity.crashExecution();
            updateVisibleState(offered.blockEntity, CobbleputerState.CRASHED);
            reportExecutionTransition(owner, executionId, ExecutionTransition.EXECUTION_TRANSITION_CRASHED);
            return;
        }
        offered.blockEntity.updateExecutionState(result.getFinalState().toByteArray());
        CobbleputerState previousState = offered.blockEntity.getBlockState().getValue(CobbleputerBlock.STATE);
        CobbleputerState nextState = status == ExecutionStatus.EXECUTION_STATUS_RUNNING
                ? CobbleputerState.RUNNING
                : CobbleputerState.STOPPED;
        updateVisibleState(offered.blockEntity, nextState);
        if (previousState != nextState) {
            reportExecutionTransition(
                    offered.blockEntity,
                    nextState == CobbleputerState.RUNNING
                            ? ExecutionTransition.EXECUTION_TRANSITION_RESUMED
                            : ExecutionTransition.EXECUTION_TRANSITION_STOPPED);
        }
    }

    private void applyEffects(CobbleputerBlockEntity blockEntity, ExecutionResult result) {
        long sequence = result.getEffectSeqNum();
        for (Effect effect : result.getEffectsList()) {
            boolean failed = false;
            long startedAt = System.nanoTime();
            try {
                applyEffect(blockEntity, effect);
            } catch (RuntimeException exception) {
                failed = true;
                logger.warn(
                        "Could not apply Cobbleputer effect at {}: {}",
                        blockEntity.getBlockPos().toShortString(),
                        exception.toString());
            } finally {
                recordEffect(blockEntity, effect, System.nanoTime() - startedAt, failed);
            }
            blockEntity.acknowledgeEffect(sequence);
            sequence += 1;
        }
    }

    private void applyEffect(CobbleputerBlockEntity blockEntity, Effect effect) {
        String message =
                switch (effect.getEffectCase()) {
                    case SET_REDSTONE_POWER -> {
                        applyRedstonePower(blockEntity, effect.getSetRedstonePower());
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
        Level level = blockEntity.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 position = Vec3.atCenterOf(blockEntity.getBlockPos());
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(position) <= CHAT_RADIUS_SQUARED) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    private static void applyRedstonePower(
            CobbleputerBlockEntity blockEntity, cobbleware.effects.SetRedstonePower effect) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        io.github.mmcgill.cobbleware.block.WirelessObserverNetwork network =
                CobblewareMod.wirelessObserverNetworkOrNull();
        if (network == null) {
            return;
        }
        Optional<UUID> observerId = effect.hasObserverId()
                ? Optional.of(new UUID(
                        effect.getObserverId().getHigh(), effect.getObserverId().getLow()))
                : Optional.empty();
        Optional<DyeColor> observerColor =
                effect.hasObserverColor() ? minecraftDyeColor(effect.getObserverColor()) : Optional.empty();
        if (effect.hasObserverColor() && observerColor.isEmpty()) {
            return;
        }
        network.setRedstonePower(
                net.minecraft.core.GlobalPos.of(level.dimension(), blockEntity.getBlockPos()),
                observerId,
                observerColor,
                effect.getPowerLevel());
    }

    private static ExecutionStatus executionStatus(BlockState blockState) {
        return switch (blockState.getValue(CobbleputerBlock.STATE)) {
            case RUNNING -> ExecutionStatus.EXECUTION_STATUS_RUNNING;
            case STOPPED -> ExecutionStatus.EXECUTION_STATUS_STOPPED;
            case UNINITIALIZED, CRASHED -> null;
        };
    }

    private static void updateVisibleState(CobbleputerBlockEntity blockEntity, CobbleputerState nextState) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        BlockState current = blockEntity.getBlockState();
        CobbleputerBlock.setLifecycleState(level, blockEntity.getBlockPos(), current, nextState);
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

    private static cobbleware.common.GlobalPos protoGlobalPos(ServerLevel level, BlockPos pos) {
        Identifier dimension = level.dimension().identifier();
        return cobbleware.common.GlobalPos.newBuilder()
                .setDimension(cobbleware.common.Identifier.newBuilder()
                        .setNs(dimension.getNamespace())
                        .setPath(dimension.getPath()))
                .setPosition(cobbleware.common.Vec3i.newBuilder()
                        .setX(pos.getX())
                        .setY(pos.getY())
                        .setZ(pos.getZ()))
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

    private void recordEventsPerSync(Flight dispatchedFlight) {
        for (OfferedExecution offered : dispatchedFlight.offered.values()) {
            instrumentationKey(offered.blockEntity)
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

    private void recordEffect(CobbleputerBlockEntity blockEntity, Effect effect, long elapsedNanos, boolean failed) {
        instrumentationKey(blockEntity)
                .ifPresent(
                        target -> instrumentation.recordEffect(target, effect.getEffectCase(), elapsedNanos, failed));
    }

    private static Optional<Instrumentation.PlayerCobbleputerKey> instrumentationKey(
            CobbleputerBlockEntity blockEntity) {
        Optional<UUID> owner = blockEntity.ownerPlayerId();
        if (owner.isEmpty() || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Identifier dimension = level.dimension().identifier();
        BlockPos pos = blockEntity.getBlockPos();
        return Optional.of(new Instrumentation.PlayerCobbleputerKey(
                owner.orElseThrow(),
                ownerName(blockEntity, owner.orElseThrow()),
                new Instrumentation.CobbleputerKey(
                        blockEntity.cobbleputerId(), dimension.toString(), pos.getX(), pos.getY(), pos.getZ())));
    }

    private void recordCobbleputerStateGauge() {
        Map<Instrumentation.PlayerCobbleputerKey, Instrumentation.LifecycleState> states = new HashMap<>();
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
            instrumentationKey(blockEntity).ifPresent(key -> states.put(key, lifecycle));
        }
        instrumentation.recordCobbleputerStates(states);
    }

    private void recordCobbleputerStateGaugeAndStream(MinecraftServer server) {
        recordCobbleputerStateGauge();
        mainWorkerClient.reportExecutionStats(
                ExecutionStatsReporter.report(instrumentation.currentTickSnapshot(), System.currentTimeMillis()));
    }

    private void reportExecutionTransition(CobbleputerBlockEntity blockEntity, ExecutionTransition transition) {
        reportExecutionTransition(
                blockEntity.ownerPlayerId().orElseThrow(),
                blockEntity.executionId().orElseThrow(),
                transition);
    }

    private void reportExecutionTransition(UUID owner, UUID executionId, ExecutionTransition transition) {
        mainWorkerClient.reportExecutionTransition(
                owner,
                executionId,
                transition,
                runningExecutionCount(owner, Collections.emptySet()),
                maxRunningExecutionsPerPlayer);
    }

    private static String ownerName(CobbleputerBlockEntity blockEntity, UUID ownerId) {
        return blockEntity.ownerPlayerName().filter(name -> !name.isEmpty()).orElse(ownerId.toString());
    }

    private static Instrumentation.SyncOutcome syncOutcome(
            MainWorkerClient.ExecutionSyncResult result, Throwable failure) {
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
    }

    private record OfferedExecution(
            CobbleputerBlockEntity blockEntity,
            long firstEventSequence,
            int offeredEventCount,
            long effectSequenceNum,
            ExecutionStatus lifecycleState) {}

    private record Flight(ExecutionsRequest request, Map<String, OfferedExecution> offered, long dispatchNanos) {
        private Flight(ExecutionsRequest request, Map<String, OfferedExecution> offered) {
            this(request, offered, -1);
        }

        private Flight withDispatchNanos(long nextDispatchNanos) {
            return new Flight(request, offered, nextDispatchNanos);
        }

        private boolean hadExecutions() {
            return !offered.isEmpty();
        }
    }
}
