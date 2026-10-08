package io.github.mmcgill.cobbleware.block;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.execution.ExecutionStateAccess;
import io.github.mmcgill.cobbleware.execution.ExecutionStateStore;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Persistent physical Cobbleputer identity and locator for its external execution record. */
public final class CobbleputerBlockEntity extends BlockEntity {
    private static final String COBBLEPUTER_ID_TAG = "cobbleputer_id";
    private static final String OWNER_PLAYER_ID_TAG = "owner_player_id";

    private final ExecutionStateAccess executionStateStore;
    private UUID cobbleputerId = UUID.randomUUID();
    private UUID executionId;
    private UUID ownerPlayerId;
    private String executionOwnerPlayerName;
    private long nextEventSequenceNum = 1;
    private long effectSequenceNum;
    private byte[] executionState;
    private Long stoppedAtTick;
    private boolean wasRunningWhenUnloaded;
    private boolean legacyExecutionStatePending;

    public CobbleputerBlockEntity(BlockPos pos, BlockState state, ExecutionStateAccess executionStateStore) {
        this(CobblewareBlockEntities.COBBLEPUTER, pos, state, executionStateStore);
    }

    /** Constructs a Cobbleputer with its block-entity type supplied by the caller. */
    public CobbleputerBlockEntity(
            BlockEntityType<?> blockEntityType,
            BlockPos pos,
            BlockState state,
            ExecutionStateAccess executionStateStore) {
        super(blockEntityType, pos, state);
        if (executionStateStore == null) {
            throw new IllegalArgumentException("execution state store must not be null");
        }
        this.executionStateStore = executionStateStore;
    }

    @Override
    public void setRemoved() {
        ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
        if (executionDriver != null && level != null && !level.isClientSide()) {
            executionDriver.deactivate(this);
        }
        super.setRemoved();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        executionStateStore.remove(this);
        clearExecution();
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.unpairLoadedObserversFor(GlobalPos.of(serverLevel.dimension(), pos));
            }
        }
        super.preRemoveSideEffects(pos, state);
    }

    /** Records the player who permanently owns this placed Cobbleputer. */
    public void initializeOwner(UUID owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        if (ownerPlayerId != null) {
            throw new IllegalStateException("Cobbleputer ownership cannot be replaced");
        }
        ownerPlayerId = owner;
        setChanged();
    }

    /** Starts a fresh execution for this Cobbleputer's permanent owner. */
    public void startExecution(String ownerName, byte[] initialState) {
        if (ownerPlayerId == null || ownerName == null || ownerName.isEmpty() || initialState == null) {
            throw new IllegalStateException("execution requires a Cobbleputer owner, owner name, and initial state");
        }
        executionId = UUID.randomUUID();
        executionOwnerPlayerName = ownerName;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = Arrays.copyOf(initialState, initialState.length);
        stoppedAtTick = null;
        wasRunningWhenUnloaded = false;
        legacyExecutionStatePending = false;
        persistExecutionState(null);
        CobblewareMod.LOGGER.info(
                "Started Cobbleputer execution {} at {} for player {}",
                executionId,
                worldPosition.toShortString(),
                ownerPlayerId);
    }

    public Optional<UUID> executionId() {
        return Optional.ofNullable(executionId);
    }

    /** Returns the durable identity of this placed Cobbleputer. */
    public UUID cobbleputerId() {
        return cobbleputerId;
    }

    public Optional<UUID> ownerPlayerId() {
        return Optional.ofNullable(ownerPlayerId);
    }

    /** Returns the name stored with this Cobbleputer's current execution record. */
    public Optional<String> executionOwnerPlayerName() {
        return Optional.ofNullable(executionOwnerPlayerName);
    }

    public long nextEventSequenceNum() {
        return nextEventSequenceNum;
    }

    /** Assigns and persists the next event sequence number. */
    public long claimNextEventSequenceNum() {
        if (!hasExecution() || nextEventSequenceNum == Long.MAX_VALUE) {
            throw new IllegalStateException("Cobbleputer event sequence cannot be advanced");
        }
        long assigned = nextEventSequenceNum;
        nextEventSequenceNum += 1;
        persistExecutionState(null);
        return assigned;
    }

    public long effectSequenceNum() {
        return effectSequenceNum;
    }

    public Optional<byte[]> executionState() {
        return executionState == null
                ? Optional.empty()
                : Optional.of(Arrays.copyOf(executionState, executionState.length));
    }

    /** Returns whether this block entity has a complete execution record. */
    public boolean hasExecution() {
        return executionId != null && ownerPlayerId != null && executionState != null;
    }

    /** Returns whether this block entity retains enough data to restart a crashed execution. */
    public boolean hasRecoverableCrashedExecution() {
        return isRecoverableCrashedExecution(executionId, ownerPlayerId, executionOwnerPlayerName, executionState);
    }

    static boolean isRecoverableCrashedExecution(
            UUID executionId, UUID ownerPlayerId, String ownerPlayerName, byte[] executionState) {
        return executionId == null
                && ownerPlayerId != null
                && ownerPlayerName != null
                && !ownerPlayerName.isEmpty()
                && executionState != null;
    }

    /** Starts a fresh execution using the owner and state retained from a crash. */
    public void restartCrashedExecution() {
        if (!hasRecoverableCrashedExecution()) {
            throw new IllegalStateException("Cobbleputer has no recoverable crashed execution");
        }
        Long crashTick = stoppedAtTick;
        startExecution(executionOwnerPlayerName, executionState);
        stoppedAtTick = crashTick;
        if (crashTick != null) {
            persistExecutionState(null);
        }
    }

    /** Replaces the durable execution state received from the Runtime. */
    public void updateExecutionState(byte[] newState) {
        if (!hasExecution() || newState == null) {
            throw new IllegalArgumentException("execution state cannot be updated without a complete execution record");
        }
        executionState = Arrays.copyOf(newState, newState.length);
        persistExecutionState(null);
    }

    /** Records that application of the next effect sequence was attempted. */
    public void acknowledgeEffect(long sequenceNum) {
        if (!hasExecution() || sequenceNum != effectSequenceNum + 1) {
            throw new IllegalArgumentException("effect acknowledgement sequence is not contiguous");
        }
        effectSequenceNum = sequenceNum;
        persistExecutionState(null);
    }

    /** Records the game tick at which this execution was stopped. */
    public void setStoppedAtTick(long tick) {
        if (!hasExecution() || tick < 0) {
            throw new IllegalArgumentException("stop tick requires a complete execution record");
        }
        stoppedAtTick = tick;
        persistExecutionState(null);
    }

    /** Returns the durable tick at which this execution last became inactive. */
    public Optional<Long> stoppedAtTick() {
        return Optional.ofNullable(stoppedAtTick);
    }

    /** Returns and clears the durable stop tick after a resume event has been enqueued. */
    public Optional<Long> takeStoppedAtTick() {
        Optional<Long> result = Optional.ofNullable(stoppedAtTick);
        stoppedAtTick = null;
        if (result.isPresent()) {
            persistExecutionState(null);
        }
        return result;
    }

    /** Records whether this execution should be resumed after its containing chunk is next loaded. */
    public void recordChunkUnloadResumeIntent(boolean wasRunning) {
        if (wasRunningWhenUnloaded == wasRunning) {
            return;
        }
        wasRunningWhenUnloaded = wasRunning;
        persistExecutionState(null);
    }

    /** Returns whether this loaded execution is awaiting one automatic resume attempt. */
    public boolean shouldResumeAfterChunkLoad() {
        return wasRunningWhenUnloaded;
    }

    /** Clears and returns the one-load automatic-resume intent. */
    public boolean takeChunkUnloadResumeIntent() {
        boolean result = wasRunningWhenUnloaded;
        wasRunningWhenUnloaded = false;
        if (result) {
            persistExecutionState(null);
        }
        return result;
    }

    /** Retires a crashed execution while retaining its owner and last successfully stored state. */
    public void crashExecution(long tick) {
        if (!hasExecution() || tick < 0) {
            throw new IllegalArgumentException("crash tick requires a complete execution record");
        }
        executionId = null;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        stoppedAtTick = tick;
        wasRunningWhenUnloaded = false;
        persistExecutionState(null);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(COBBLEPUTER_ID_TAG, UUIDUtil.CODEC, cobbleputerId);
        output.storeNullable(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC, ownerPlayerId);
        if (legacyExecutionStatePending) {
            saveLegacyExecutionState(output);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        cobbleputerId = input.read(COBBLEPUTER_ID_TAG, UUIDUtil.CODEC)
                .orElseThrow(() -> new IllegalStateException("Missing required cobbleputer_id"));
        ownerPlayerId = input.read(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC).orElse(null);
        loadLegacyExecutionState(input);
    }

    /** Maps both current data and legacy persisted RUNNING records to a deferred resume decision. */
    static boolean shouldResumeAfterChunkLoad(
            CobbleputerState lifecycleState, Optional<Boolean> storedWasRunningWhenUnloaded) {
        return lifecycleState == CobbleputerState.RUNNING || storedWasRunningWhenUnloaded.orElse(false);
    }

    /** Returns the external-file representation of this block entity's execution, if it has one. */
    public Optional<ExecutionStateStore.ExecutionRecord> executionRecord() {
        boolean validCrashedRecord = getBlockState().getValue(CobbleputerBlock.STATE) == CobbleputerState.CRASHED
                && isRecoverableCrashedExecution(executionId, ownerPlayerId, executionOwnerPlayerName, executionState)
                && nextEventSequenceNum >= 1
                && effectSequenceNum >= 0
                && (stoppedAtTick == null || stoppedAtTick >= 0);
        boolean validActiveRecord = executionId != null
                && ownerPlayerId != null
                && executionState != null
                && nextEventSequenceNum >= 1
                && effectSequenceNum >= 0
                && (stoppedAtTick == null || stoppedAtTick >= 0);
        if (!validCrashedRecord && !validActiveRecord) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionStateStore.ExecutionRecord(
                executionId,
                executionOwnerPlayerName,
                executionState,
                nextEventSequenceNum,
                effectSequenceNum,
                stoppedAtTick,
                wasRunningWhenUnloaded));
    }

    /** Hydrates the non-persistent in-memory fields from a validated owner-file record. */
    public void loadExecutionRecord(ExecutionStateStore.ExecutionRecord record) {
        if (record == null || ownerPlayerId == null) {
            throw new IllegalArgumentException("execution record and its owner locator must be present");
        }
        executionId = record.executionId();
        executionOwnerPlayerName = record.ownerPlayerName();
        executionState = record.executionState();
        nextEventSequenceNum = record.nextEventSequenceNum();
        effectSequenceNum = record.effectSequenceNum();
        stoppedAtTick = record.stoppedAtTick();
        wasRunningWhenUnloaded = record.wasRunningWhenUnloaded();
    }

    /** Returns whether an external record satisfies this block's current lifecycle invariants. */
    public boolean acceptsExecutionRecord(ExecutionStateStore.ExecutionRecord record) {
        if (record == null || ownerPlayerId == null) {
            return false;
        }
        boolean validCrashedRecord = getBlockState().getValue(CobbleputerBlock.STATE) == CobbleputerState.CRASHED
                && isRecoverableCrashedExecution(
                        record.executionId(), ownerPlayerId, record.ownerPlayerName(), record.executionState())
                && record.nextEventSequenceNum() >= 1
                && record.effectSequenceNum() >= 0
                && (record.stoppedAtTick() == null || record.stoppedAtTick() >= 0);
        boolean validActiveRecord = getBlockState().getValue(CobbleputerBlock.STATE) != CobbleputerState.CRASHED
                && record.executionId() != null
                && record.executionState() != null
                && record.nextEventSequenceNum() >= 1
                && record.effectSequenceNum() >= 0
                && (record.stoppedAtTick() == null || record.stoppedAtTick() >= 0);
        return validCrashedRecord || validActiveRecord;
    }

    /** Returns whether this entity still carries a legacy NBT execution record awaiting migration. */
    public boolean hasLegacyExecutionState() {
        return legacyExecutionStatePending;
    }

    /** Drops legacy NBT persistence after a safe server-thread migration completion. */
    public void completeLegacyMigration() {
        if (!legacyExecutionStatePending) {
            return;
        }
        legacyExecutionStatePending = false;
        setChanged();
    }

    private void saveLegacyExecutionState(ValueOutput output) {
        LegacyCobbleputerExecutionStateMigrator.write(
                output,
                new LegacyCobbleputerExecutionStateMigrator.LegacyExecutionRecord(
                        executionId,
                        executionOwnerPlayerName,
                        executionState,
                        nextEventSequenceNum,
                        effectSequenceNum,
                        stoppedAtTick,
                        wasRunningWhenUnloaded));
    }

    private void loadLegacyExecutionState(ValueInput input) {
        Optional<LegacyCobbleputerExecutionStateMigrator.LegacyExecutionRecord> legacy =
                LegacyCobbleputerExecutionStateMigrator.read(
                        input, getBlockState().getValue(CobbleputerBlock.STATE), ownerPlayerId);
        if (legacy.isEmpty()) {
            executionId = null;
            executionOwnerPlayerName = null;
            executionState = null;
            nextEventSequenceNum = 1;
            effectSequenceNum = 0;
            stoppedAtTick = null;
            wasRunningWhenUnloaded = false;
            legacyExecutionStatePending = false;
            return;
        }
        LegacyCobbleputerExecutionStateMigrator.LegacyExecutionRecord record = legacy.orElseThrow();
        executionId = record.executionId();
        executionOwnerPlayerName = record.ownerPlayerName();
        executionState = record.executionState();
        nextEventSequenceNum = record.nextEventSequenceNum();
        effectSequenceNum = record.effectSequenceNum();
        stoppedAtTick = record.stoppedAtTick();
        wasRunningWhenUnloaded = record.wasRunningWhenUnloaded();
        legacyExecutionStatePending = true;
    }

    private void persistExecutionState(UUID priorOwner) {
        executionStateStore.update(this, priorOwner);
    }

    private void clearExecution() {
        executionId = null;
        ownerPlayerId = null;
        executionOwnerPlayerName = null;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = null;
        stoppedAtTick = null;
        wasRunningWhenUnloaded = false;
        legacyExecutionStatePending = false;
    }
}
