package io.github.mmcgill.cobbleware.block;

import com.mojang.serialization.Codec;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Durable execution record for a placed Cobbleputer. */
public final class CobbleputerBlockEntity extends BlockEntity {
    private static final String COBBLEPUTER_ID_TAG = "cobbleputer_id";
    private static final String EXECUTION_ID_TAG = "execution_id";
    private static final String OWNER_PLAYER_ID_TAG = "owner_player_id";
    private static final String OWNER_PLAYER_NAME_TAG = "owner_player_name";
    private static final String NEXT_EVENT_SEQUENCE_NUM_TAG = "next_event_sequence_num";
    private static final String EFFECT_SEQUENCE_NUM_TAG = "effect_sequence_num";
    private static final String EXECUTION_STATE_TAG = "execution_state";
    private static final String STOPPED_AT_TICK_TAG = "stopped_at_tick";

    private UUID cobbleputerId = UUID.randomUUID();
    private boolean cobbleputerIdNeedsPersistence = true;
    private UUID executionId;
    private UUID ownerPlayerId;
    private String ownerPlayerName;
    private long nextEventSequenceNum = 1;
    private long effectSequenceNum;
    private byte[] executionState;
    private Long stoppedAtTick;

    public CobbleputerBlockEntity(BlockPos pos, BlockState state) {
        super(CobblewareBlockEntities.COBBLEPUTER, pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
        if (executionDriver != null && !level.isClientSide()) {
            executionDriver.activate(this);
        }
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
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.unpairLoadedObserversFor(GlobalPos.of(serverLevel.dimension(), pos));
            }
        }
        super.preRemoveSideEffects(pos, state);
    }

    /** Starts a fresh execution owned by {@code owner}, replacing any previous execution record. */
    public void startExecution(UUID owner, String ownerName, byte[] initialState) {
        if (owner == null || ownerName == null || ownerName.isEmpty() || initialState == null) {
            throw new IllegalArgumentException("execution owner, owner name, and initial state must not be null or empty");
        }

        executionId = UUID.randomUUID();
        ownerPlayerId = owner;
        ownerPlayerName = ownerName;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = Arrays.copyOf(initialState, initialState.length);
        stoppedAtTick = null;
        setChanged();
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

    /** Returns whether this generated ID still needs a normal-tick persistence pass. */
    public boolean cobbleputerIdNeedsPersistence() {
        return cobbleputerIdNeedsPersistence;
    }

    public Optional<UUID> ownerPlayerId() {
        return Optional.ofNullable(ownerPlayerId);
    }

    /** Returns the owner name recorded when the current execution was started. */
    public Optional<String> ownerPlayerName() {
        return Optional.ofNullable(ownerPlayerName);
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
        setChanged();
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

    /** Replaces the durable execution state received from the Runtime. */
    public void updateExecutionState(byte[] newState) {
        if (!hasExecution() || newState == null) {
            throw new IllegalArgumentException("execution state cannot be updated without a complete execution record");
        }
        executionState = Arrays.copyOf(newState, newState.length);
        setChanged();
    }

    /** Records that application of the next effect sequence was attempted. */
    public void acknowledgeEffect(long sequenceNum) {
        if (!hasExecution() || sequenceNum != effectSequenceNum + 1) {
            throw new IllegalArgumentException("effect acknowledgement sequence is not contiguous");
        }
        effectSequenceNum = sequenceNum;
        setChanged();
    }

    /** Records the game tick at which this execution was stopped. */
    public void setStoppedAtTick(long tick) {
        if (!hasExecution() || tick < 0) {
            throw new IllegalArgumentException("stop tick requires a complete execution record");
        }
        stoppedAtTick = tick;
        setChanged();
    }

    /** Returns and clears the durable stop tick after a resume event has been enqueued. */
    public Optional<Long> takeStoppedAtTick() {
        Optional<Long> result = Optional.ofNullable(stoppedAtTick);
        stoppedAtTick = null;
        if (result.isPresent()) {
            setChanged();
        }
        return result;
    }

    /** Clears a crashed execution record. */
    public void crashExecution() {
        clearExecution();
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(COBBLEPUTER_ID_TAG, UUIDUtil.CODEC, cobbleputerId);
        cobbleputerIdNeedsPersistence = false;
        output.storeNullable(EXECUTION_ID_TAG, UUIDUtil.CODEC, executionId);
        output.storeNullable(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC, ownerPlayerId);
        if (ownerPlayerName != null) {
            output.putString(OWNER_PLAYER_NAME_TAG, ownerPlayerName);
        }
        output.putLong(NEXT_EVENT_SEQUENCE_NUM_TAG, nextEventSequenceNum);
        output.putLong(EFFECT_SEQUENCE_NUM_TAG, effectSequenceNum);
        if (executionState != null) {
            output.store(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER, ByteBuffer.wrap(executionState));
        }
        if (stoppedAtTick != null) {
            output.putLong(STOPPED_AT_TICK_TAG, stoppedAtTick);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        Optional<UUID> loadedCobbleputerId = input.read(COBBLEPUTER_ID_TAG, UUIDUtil.CODEC);
        cobbleputerId = loadedCobbleputerId.orElseGet(UUID::randomUUID);
        cobbleputerIdNeedsPersistence = loadedCobbleputerId.isEmpty();
        executionId = input.read(EXECUTION_ID_TAG, UUIDUtil.CODEC).orElse(null);
        ownerPlayerId = input.read(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC).orElse(null);
        ownerPlayerName = input.getStringOr(OWNER_PLAYER_NAME_TAG, "");
        nextEventSequenceNum = input.getLongOr(NEXT_EVENT_SEQUENCE_NUM_TAG, 1);
        effectSequenceNum = input.getLongOr(EFFECT_SEQUENCE_NUM_TAG, 0);
        executionState = input.read(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER)
                .map(CobbleputerBlockEntity::copyBytes)
                .orElse(null);
        long loadedStoppedAtTick = input.getLongOr(STOPPED_AT_TICK_TAG, -1);
        stoppedAtTick = loadedStoppedAtTick < 0 ? null : loadedStoppedAtTick;

        if (executionId == null || ownerPlayerId == null || executionState == null || nextEventSequenceNum < 1
                || effectSequenceNum < 0 || (stoppedAtTick != null && stoppedAtTick < 0)) {
            clearExecution();
        }
    }

    private void clearExecution() {
        executionId = null;
        ownerPlayerId = null;
        ownerPlayerName = null;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = null;
        stoppedAtTick = null;
    }

    /** Persists a Cobbleputer ID generated for new or legacy block-entity data after chunk attachment. */
    public void persistMigratedCobbleputerId() {
        if (!cobbleputerIdNeedsPersistence) {
            return;
        }
        cobbleputerIdNeedsPersistence = false;
        setChanged();
    }

    private static byte[] copyBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
