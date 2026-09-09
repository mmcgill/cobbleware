package io.github.mmcgill.cobbleware.execution;

import cobbleware.common.Uuid;
import cobbleware.effects.Effect;
import cobbleware.events.ChatMessage;
import cobbleware.events.Event;
import cobbleware.events.Resumed;
import cobbleware.events.Started;
import cobbleware.internal.ExecutionResult;
import cobbleware.internal.ExecutionState;
import cobbleware.internal.ExecutionStatus;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Drives the best-effort synchronization of active Cobbleputer executions. */
public final class ExecutionDriver {
    private static final int MAX_PENDING_EVENTS_PER_EXECUTION = 10;
    private static final int MAX_OFFERED_EVENTS_PER_EXECUTION = 10;
    private static final double CHAT_RADIUS = 16.0;
    private static final double CHAT_RADIUS_SQUARED = CHAT_RADIUS * CHAT_RADIUS;

    private final MainWorkerClient mainWorkerClient;
    private final Logger logger;
    private final Map<CobbleputerBlockEntity, ActiveExecution> activeExecutions = new IdentityHashMap<>();

    private CompletableFuture<MainWorkerClient.ExecutionSyncResult> inFlight;
    private Flight flight;
    private boolean runtimeMayHaveExecutions;

    public ExecutionDriver(MainWorkerClient mainWorkerClient, Logger logger) {
        if (mainWorkerClient == null || logger == null) {
            throw new IllegalArgumentException("ExecutionDriver dependencies must not be null");
        }
        this.mainWorkerClient = mainWorkerClient;
        this.logger = logger;
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, parameters) -> dispatchChatMessage(sender, message.signedContent()));
    }

    /** Adds a loaded Cobbleputer to the driver's active set. */
    public void activate(CobbleputerBlockEntity blockEntity) {
        activeExecutions.putIfAbsent(blockEntity, new ActiveExecution());
    }

    /** Removes a Cobbleputer and intentionally drops its ephemeral pending events. */
    public void deactivate(CobbleputerBlockEntity blockEntity) {
        activeExecutions.remove(blockEntity);
    }

    /** Starts a fresh execution and queues its first lifecycle event. */
    public void startExecution(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            CobbleputerBlockEntity blockEntity,
            UUID owner,
            byte[] initialState) {
        activate(blockEntity);
        blockEntity.startExecution(owner, initialState);
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.RUNNING)) {
            enqueueEvent(blockEntity, Event.newBuilder()
                    .setStarted(Started.newBuilder().setTick(level.getGameTime()))
                    .build());
        }
    }

    /** Stops a running execution without discarding its execution record or queued events. */
    public void stop(ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasExecution() || state.getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            return;
        }
        activate(blockEntity);
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.STOPPED)) {
            blockEntity.setStoppedAtTick(level.getGameTime());
        }
    }

    /** Resumes a stopped execution and queues its resume lifecycle event. */
    public void resume(ServerLevel level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {
        if (!blockEntity.hasExecution() || state.getValue(CobbleputerBlock.STATE) != CobbleputerState.STOPPED) {
            return;
        }
        activate(blockEntity);
        long resumeTick = level.getGameTime();
        if (CobbleputerBlock.setLifecycleState(level, pos, state, CobbleputerState.RUNNING)) {
            long stopTick = blockEntity.takeStoppedAtTick().orElse(resumeTick);
            enqueueEvent(blockEntity, Event.newBuilder()
                    .setResumed(Resumed.newBuilder().setStopTick(stopTick).setResumeTick(resumeTick))
                    .build());
        }
    }

    /** Queues an event for a running active Cobbleputer, or drops it when it cannot be accepted. */
    public void enqueueEvent(CobbleputerBlockEntity blockEntity, Event event) {
        if (blockEntity == null || event == null) {
            throw new IllegalArgumentException("Cobbleputer event arguments must not be null");
        }
        ActiveExecution active = activeExecutions.get(blockEntity);
        if (active != null) {
            enqueueActiveEvent(blockEntity, active, event);
        }
    }

    /** Delivers raw player chat to every eligible active Cobbleputer in the sender's level and range. */
    private void dispatchChatMessage(ServerPlayer sender, String message) {
        Event event = Event.newBuilder()
                .setChatMessage(ChatMessage.newBuilder().setMessage(message))
                .build();
        Level senderLevel = sender.level();
        for (Map.Entry<CobbleputerBlockEntity, ActiveExecution> entry : activeExecutions.entrySet()) {
            CobbleputerBlockEntity blockEntity = entry.getKey();
            if (blockEntity.getLevel() != senderLevel
                    || sender.distanceToSqr(Vec3.atCenterOf(blockEntity.getBlockPos())) > CHAT_RADIUS_SQUARED) {
                continue;
            }
            enqueueActiveEvent(blockEntity, entry.getValue(), event);
        }
    }

    private void enqueueActiveEvent(CobbleputerBlockEntity blockEntity, ActiveExecution active, Event event) {
        if (active == null || !blockEntity.hasExecution()
                || blockEntity.getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.RUNNING) {
            return;
        }
        if (active.events.size() >= MAX_PENDING_EVENTS_PER_EXECUTION) {
            logger.warn("Dropping Cobbleputer event at {} because its pending queue is full",
                    blockEntity.getBlockPos().toShortString());
            return;
        }
        try {
            active.events.addLast(new QueuedEvent(blockEntity.claimNextEventSequenceNum(), event));
        } catch (RuntimeException exception) {
            logger.error("Could not enqueue Cobbleputer event at {}: {}",
                    blockEntity.getBlockPos().toShortString(), exception.toString());
        }
    }

    private void tick(MinecraftServer server) {
        if (inFlight != null) {
            if (!inFlight.isDone()) {
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

        Flight nextFlight = buildFlight();
        if (!nextFlight.hadExecutions() && !runtimeMayHaveExecutions) {
            return;
        }
        if (nextFlight.hadExecutions()) {
            runtimeMayHaveExecutions = true;
        }
        flight = nextFlight;
        inFlight = mainWorkerClient.syncExecutions(nextFlight.request);
    }

    private Flight buildFlight() {
        List<ExecutionState> executions = new ArrayList<>();
        Map<String, OfferedExecution> offered = new HashMap<>();
        for (Map.Entry<CobbleputerBlockEntity, ActiveExecution> entry : activeExecutions.entrySet()) {
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
                if (events.size() == MAX_OFFERED_EVENTS_PER_EXECUTION) {
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
            offered.put(uuidKey(executionId), new OfferedExecution(
                    blockEntity, firstEventSequence, events.size(), blockEntity.effectSequenceNum(), status));
        }
        return new Flight(ExecutionsRequest.newBuilder().addAllExecutions(executions).build(), offered);
    }

    private void applyResponse(ExecutionsResponse response, Flight completedFlight) {
        if (completedFlight == null || !hasExpectedResults(response, completedFlight)) {
            logger.warn("Discarding invalid execution-sync response");
            return;
        }
        for (ExecutionResult result : response.getResultsList()) {
            OfferedExecution offered = completedFlight.offered.get(uuidKey(result.getExecutionId()));
            if (offered == null || activeExecutions.get(offered.blockEntity) == null
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
            logger.warn("Discarding execution result with invalid lifecycle state at {}",
                    offered.blockEntity.getBlockPos().toShortString());
            return;
        }
        long minimumConsumed = offered.firstEventSequence - 1;
        long maximumConsumed = offered.firstEventSequence + offered.offeredEventCount - 1;
        if (result.getEventSeqNum() < minimumConsumed || result.getEventSeqNum() > maximumConsumed
                || result.getEffectSeqNum() != offered.effectSequenceNum + 1) {
            logger.warn("Discarding execution result with invalid sequence numbers at {}",
                    offered.blockEntity.getBlockPos().toShortString());
            return;
        }

        ActiveExecution active = activeExecutions.get(offered.blockEntity);
        while (!active.events.isEmpty() && active.events.getFirst().sequenceNum <= result.getEventSeqNum()) {
            active.events.removeFirst();
        }
        applyEffects(offered.blockEntity, result);
        if (status == ExecutionStatus.EXECUTION_STATUS_CRASHED) {
            active.events.clear();
            offered.blockEntity.crashExecution();
            updateVisibleState(offered.blockEntity, CobbleputerState.CRASHED);
            return;
        }
        offered.blockEntity.updateExecutionState(result.getFinalState().toByteArray());
        updateVisibleState(offered.blockEntity, status == ExecutionStatus.EXECUTION_STATUS_RUNNING
                ? CobbleputerState.RUNNING
                : CobbleputerState.STOPPED);
    }

    private void applyEffects(CobbleputerBlockEntity blockEntity, ExecutionResult result) {
        long sequence = result.getEffectSeqNum();
        for (Effect effect : result.getEffectsList()) {
            try {
                applyEffect(blockEntity, effect);
            } catch (RuntimeException exception) {
                logger.warn("Could not apply Cobbleputer effect at {}: {}",
                        blockEntity.getBlockPos().toShortString(), exception.toString());
            }
            blockEntity.acknowledgeEffect(sequence);
            sequence += 1;
        }
    }

    private void applyEffect(CobbleputerBlockEntity blockEntity, Effect effect) {
        String message = switch (effect.getEffectCase()) {
            case EMIT_BEEP -> {
                if (Integer.compareUnsigned(effect.getEmitBeep().getMillis(), 65535) > 0) {
                    logger.warn("Ignoring EmitBeep with an invalid millis value at {}",
                            blockEntity.getBlockPos().toShortString());
                    yield null;
                }
                yield "beep";
            }
            case SEND_CHAT -> effect.getSendChat().getMsg();
            case EFFECT_NOT_SET -> null;
        };
        if (message == null) {
            logger.warn("Ignoring unsupported or unset Cobbleputer effect at {}",
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

    private static String uuidKey(UUID uuid) {
        return uuid.getMostSignificantBits() + ":" + uuid.getLeastSignificantBits();
    }

    private static String uuidKey(Uuid uuid) {
        return uuid.getHigh() + ":" + uuid.getLow();
    }

    private record QueuedEvent(long sequenceNum, Event event) {
    }

    private static final class ActiveExecution {
        private final ArrayDeque<QueuedEvent> events = new ArrayDeque<>();
    }

    private record OfferedExecution(
            CobbleputerBlockEntity blockEntity,
            long firstEventSequence,
            int offeredEventCount,
            long effectSequenceNum,
            ExecutionStatus lifecycleState) {
    }

    private record Flight(ExecutionsRequest request, Map<String, OfferedExecution> offered) {
        private boolean hadExecutions() {
            return !offered.isEmpty();
        }
    }
}
