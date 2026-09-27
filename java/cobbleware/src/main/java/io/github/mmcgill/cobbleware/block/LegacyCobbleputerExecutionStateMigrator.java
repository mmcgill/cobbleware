package io.github.mmcgill.cobbleware.block;

import com.mojang.serialization.Codec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * One-release reader/writer for execution records formerly stored in Cobbleputer block entities.
 * Remove this class and the temporary legacy fields in {@link CobbleputerBlockEntity} after the migration window.
 */
final class LegacyCobbleputerExecutionStateMigrator {
    private static final String EXECUTION_ID_TAG = "execution_id";
    private static final String OWNER_PLAYER_NAME_TAG = "owner_player_name";
    private static final String NEXT_EVENT_SEQUENCE_NUM_TAG = "next_event_sequence_num";
    private static final String EFFECT_SEQUENCE_NUM_TAG = "effect_sequence_num";
    private static final String EXECUTION_STATE_TAG = "execution_state";
    private static final String STOPPED_AT_TICK_TAG = "stopped_at_tick";
    private static final String WAS_RUNNING_WHEN_UNLOADED_TAG = "was_running_when_unloaded";

    private LegacyCobbleputerExecutionStateMigrator() {}

    static Optional<LegacyExecutionRecord> read(ValueInput input, CobbleputerState lifecycleState, UUID ownerPlayerId) {
        byte[] executionState = input.read(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER)
                .map(LegacyCobbleputerExecutionStateMigrator::copyBytes)
                .orElse(null);
        if (executionState == null) {
            return Optional.empty();
        }
        UUID executionId = input.read(EXECUTION_ID_TAG, UUIDUtil.CODEC).orElse(null);
        String ownerPlayerName = input.getStringOr(OWNER_PLAYER_NAME_TAG, "");
        long nextEventSequenceNum = input.getLongOr(NEXT_EVENT_SEQUENCE_NUM_TAG, 1);
        long effectSequenceNum = input.getLongOr(EFFECT_SEQUENCE_NUM_TAG, 0);
        long loadedStoppedAtTick = input.getLongOr(STOPPED_AT_TICK_TAG, -1);
        Long stoppedAtTick = loadedStoppedAtTick < 0 ? null : loadedStoppedAtTick;
        boolean wasRunningWhenUnloaded = CobbleputerBlockEntity.shouldResumeAfterChunkLoad(
                lifecycleState, input.read(WAS_RUNNING_WHEN_UNLOADED_TAG, Codec.BOOL));
        if (lifecycleState == CobbleputerState.CRASHED) {
            wasRunningWhenUnloaded = false;
        }
        LegacyExecutionRecord record = new LegacyExecutionRecord(
                executionId,
                ownerPlayerName,
                executionState,
                nextEventSequenceNum,
                effectSequenceNum,
                stoppedAtTick,
                wasRunningWhenUnloaded);
        return record.isValid(lifecycleState, ownerPlayerId) ? Optional.of(record) : Optional.empty();
    }

    static void write(ValueOutput output, LegacyExecutionRecord record) {
        output.storeNullable(EXECUTION_ID_TAG, UUIDUtil.CODEC, record.executionId());
        if (record.ownerPlayerName() != null) {
            output.putString(OWNER_PLAYER_NAME_TAG, record.ownerPlayerName());
        }
        output.putLong(NEXT_EVENT_SEQUENCE_NUM_TAG, record.nextEventSequenceNum());
        output.putLong(EFFECT_SEQUENCE_NUM_TAG, record.effectSequenceNum());
        output.store(EXECUTION_STATE_TAG, Codec.BYTE_BUFFER, ByteBuffer.wrap(record.executionState()));
        if (record.stoppedAtTick() != null) {
            output.putLong(STOPPED_AT_TICK_TAG, record.stoppedAtTick());
        }
        output.putBoolean(WAS_RUNNING_WHEN_UNLOADED_TAG, record.wasRunningWhenUnloaded());
    }

    record LegacyExecutionRecord(
            UUID executionId,
            String ownerPlayerName,
            byte[] executionState,
            long nextEventSequenceNum,
            long effectSequenceNum,
            Long stoppedAtTick,
            boolean wasRunningWhenUnloaded) {
        LegacyExecutionRecord {
            executionState = Arrays.copyOf(executionState, executionState.length);
        }

        @Override
        public byte[] executionState() {
            return Arrays.copyOf(executionState, executionState.length);
        }

        private boolean isValid(CobbleputerState lifecycleState, UUID ownerPlayerId) {
            boolean validCrashed = lifecycleState == CobbleputerState.CRASHED
                    && executionId == null
                    && ownerPlayerId != null
                    && ownerPlayerName != null
                    && !ownerPlayerName.isEmpty()
                    && nextEventSequenceNum >= 1
                    && effectSequenceNum >= 0
                    && stoppedAtTick == null;
            boolean validActive = lifecycleState != CobbleputerState.CRASHED
                    && executionId != null
                    && ownerPlayerId != null
                    && nextEventSequenceNum >= 1
                    && effectSequenceNum >= 0
                    && (stoppedAtTick == null || stoppedAtTick >= 0);
            return validCrashed || validActive;
        }
    }

    private static byte[] copyBytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
