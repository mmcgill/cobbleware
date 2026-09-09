package io.github.mmcgill.cobbleware.block;

import com.mojang.serialization.Codec;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Durable execution record for a placed Cobbleputer. */
public final class CobbleputerBlockEntity extends BlockEntity {
    private static final String EXECUTION_ID_TAG = "execution_id";
    private static final String OWNER_PLAYER_ID_TAG = "owner_player_id";
    private static final String NEXT_EVENT_SEQUENCE_NUM_TAG = "next_event_sequence_num";
    private static final String EFFECT_SEQUENCE_NUM_TAG = "effect_sequence_num";
    private static final String EXECUTION_STATE_TAG = "execution_state";

    private UUID executionId;
    private UUID ownerPlayerId;
    private long nextEventSequenceNum = 1;
    private long effectSequenceNum;
    private byte[] executionState;

    public CobbleputerBlockEntity(BlockPos pos, BlockState state) {
        super(CobblewareBlockEntities.COBBLEPUTER, pos, state);
    }

    /** Starts a fresh execution owned by {@code owner}, replacing any previous execution record. */
    public void startExecution(UUID owner, byte[] initialState) {
        if (owner == null || initialState == null) {
            throw new IllegalArgumentException("execution owner and initial state must not be null");
        }

        executionId = UUID.randomUUID();
        ownerPlayerId = owner;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = Arrays.copyOf(initialState, initialState.length);
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

    public Optional<UUID> ownerPlayerId() {
        return Optional.ofNullable(ownerPlayerId);
    }

    public long nextEventSequenceNum() {
        return nextEventSequenceNum;
    }

    public long effectSequenceNum() {
        return effectSequenceNum;
    }

    public Optional<byte[]> executionState() {
        return executionState == null
                ? Optional.empty()
                : Optional.of(Arrays.copyOf(executionState, executionState.length));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable(EXECUTION_ID_TAG, UUIDUtil.CODEC, executionId);
        output.storeNullable(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC, ownerPlayerId);
        output.putLong(NEXT_EVENT_SEQUENCE_NUM_TAG, nextEventSequenceNum);
        output.putLong(EFFECT_SEQUENCE_NUM_TAG, effectSequenceNum);
        if (executionState != null) {
            output.store(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER, ByteBuffer.wrap(executionState));
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        executionId = input.read(EXECUTION_ID_TAG, UUIDUtil.CODEC).orElse(null);
        ownerPlayerId = input.read(OWNER_PLAYER_ID_TAG, UUIDUtil.CODEC).orElse(null);
        nextEventSequenceNum = input.getLongOr(NEXT_EVENT_SEQUENCE_NUM_TAG, 1);
        effectSequenceNum = input.getLongOr(EFFECT_SEQUENCE_NUM_TAG, 0);
        executionState = input.read(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER)
                .map(CobbleputerBlockEntity::copyBytes)
                .orElse(null);

        if (executionId == null || ownerPlayerId == null || executionState == null || nextEventSequenceNum < 1
                || effectSequenceNum < 0) {
            clearExecution();
        }
    }

    private void clearExecution() {
        executionId = null;
        ownerPlayerId = null;
        nextEventSequenceNum = 1;
        effectSequenceNum = 0;
        executionState = null;
    }

    private static byte[] copyBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
