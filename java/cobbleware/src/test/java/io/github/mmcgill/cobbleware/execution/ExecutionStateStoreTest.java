package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cobbleware.internal.PlayerExecutionStates;
import cobbleware.internal.StoredExecutionState;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ExecutionStateStoreTest {
    @Test
    void rejectsOperationsBeforeInitialization() {
        ExecutionStateStore store = new ExecutionStateStore(LoggerFactory.getLogger(ExecutionStateStoreTest.class));

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> store.attach(null)),
                () -> assertThrows(IllegalStateException.class, () -> store.update(null, null)),
                () -> assertThrows(IllegalStateException.class, () -> store.remove(null)),
                () -> assertThrows(IllegalStateException.class, () -> store.stateSizeLimit(null, null, 0)),
                () -> assertThrows(IllegalStateException.class, () -> store.fitsStateUpdate(null, null, null, 0)),
                () -> assertThrows(IllegalStateException.class, () -> store.chatTokenCensus(0)),
                () -> assertThrows(IllegalStateException.class, store::stateSizeCensus),
                () -> assertThrows(IllegalStateException.class, store::beforeSave),
                () -> assertThrows(IllegalStateException.class, store::close));
    }

    @Test
    void roundTripsActiveAndCrashedExecutionRecords() throws Exception {
        UUID activeCobbleputer = UUID.randomUUID();
        UUID crashedCobbleputer = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        byte[] activeState = {1, 2, 3};
        byte[] crashedState = {4, 5};
        Map<UUID, ExecutionStateStore.ExecutionRecord> records = Map.of(
                activeCobbleputer,
                new ExecutionStateStore.ExecutionRecord(execution, activeState, 8, 3, 42L, true),
                crashedCobbleputer,
                new ExecutionStateStore.ExecutionRecord(null, crashedState, 1, 0, null, false));

        Map<UUID, ExecutionStateStore.ExecutionRecord> decoded =
                ExecutionStateStore.decode(ExecutionStateStore.encode(records));

        assertEquals(execution, decoded.get(activeCobbleputer).executionId());
        assertArrayEquals(activeState, decoded.get(activeCobbleputer).executionState());
        assertEquals(8, decoded.get(activeCobbleputer).nextEventSequenceNum());
        assertEquals(3, decoded.get(activeCobbleputer).effectSequenceNum());
        assertEquals(42L, decoded.get(activeCobbleputer).stoppedAtTick());
        assertEquals(true, decoded.get(activeCobbleputer).wasRunningWhenUnloaded());
        assertEquals(null, decoded.get(crashedCobbleputer).executionId());
        assertArrayEquals(crashedState, decoded.get(crashedCobbleputer).executionState());
    }

    @Test
    void rejectsAnUnsupportedFileFormatVersion() {
        byte[] bytes =
                PlayerExecutionStates.newBuilder().setFormatVersion(2).build().toByteArray();

        assertThrows(IOException.class, () -> ExecutionStateStore.decode(bytes));
    }

    @Test
    void readsLegacyOwnerNamesAndWritesFullVersionTwoChatBucketsWithoutThem() throws Exception {
        UUID cobbleputer = UUID.randomUUID();
        byte[] versionOne = legacyVersionOneRecord(cobbleputer, "owner");

        assertTrue(PlayerExecutionStates.parseFrom(versionOne)
                .getExecutions(0)
                .getUnknownFields()
                .hasField(3));
        assertEquals(1, ExecutionStateStore.decode(versionOne).size());
        PlayerExecutionStates migrated = PlayerExecutionStates.parseFrom(ExecutionStateStore.encode(Map.of(
                cobbleputer,
                new ExecutionStateStore.ExecutionRecord(UUID.randomUUID(), new byte[] {1}, 1, 0, null, false))));

        assertEquals(2, migrated.getFormatVersion());
        assertFalse(migrated.getExecutions(0).getUnknownFields().hasField(3));
        assertEquals(10, migrated.getBurstChatTokens());
        assertEquals(100, migrated.getSlowChatTokens());
        assertEquals(100, migrated.getNextBurstChatTransferTick());
        assertEquals(400, migrated.getNextSlowChatRefillTick());
    }

    @Test
    void chatBucketTransfersAndRefillsOnlyAfterItsGameTimeDeadlines() {
        ExecutionStateStore.ChatTokenBucket bucket = ExecutionStateStore.ChatTokenBucket.uninitialized();

        assertEquals(true, bucket.advance(100));
        assertEquals(new ExecutionStateAccess.ChatTokens(10, 100), bucket.snapshot());
        for (int index = 0; index < 10; index += 1) {
            assertEquals(true, bucket.tryConsumeBurst());
        }
        assertEquals(false, bucket.tryConsumeBurst());
        assertEquals(false, bucket.advance(199));
        assertEquals(new ExecutionStateAccess.ChatTokens(0, 100), bucket.snapshot());
        assertEquals(true, bucket.advance(200));
        assertEquals(new ExecutionStateAccess.ChatTokens(1, 99), bucket.snapshot());
    }

    @Test
    void executionRecordDefensivelyCopiesStateBytes() {
        byte[] original = {1, 2};
        ExecutionStateStore.ExecutionRecord record =
                new ExecutionStateStore.ExecutionRecord(UUID.randomUUID(), original, 1, 0, null, false);
        original[0] = 9;
        byte[] returned = record.executionState();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, record.executionState());
    }

    @Test
    void atomicallyReplacesAnExistingOwnerFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("owner.pb");
        Files.write(file, new byte[] {1, 2});

        ExecutionStateStore.writeAtomically(file, new byte[] {3, 4, 5});

        assertArrayEquals(new byte[] {3, 4, 5}, Files.readAllBytes(file));
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count());
        }
    }

    private static byte[] legacyVersionOneRecord(UUID cobbleputerId, String ownerName) throws Exception {
        StoredExecutionState currentRecord = StoredExecutionState.newBuilder()
                .setCobbleputerId(cobbleware.common.Uuid.newBuilder()
                        .setHigh(cobbleputerId.getMostSignificantBits())
                        .setLow(cobbleputerId.getLeastSignificantBits()))
                .setExecutionState(ByteString.copyFrom(new byte[] {1}))
                .setNextEventSequenceNum(1)
                .setEffectSequenceNum(0)
                .setWasRunningWhenUnloaded(false)
                .build();
        ByteArrayOutputStream storedBytes = new ByteArrayOutputStream();
        CodedOutputStream storedOutput = CodedOutputStream.newInstance(storedBytes);
        storedOutput.writeRawBytes(currentRecord.toByteArray());
        storedOutput.writeString(3, ownerName);
        storedOutput.flush();

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        CodedOutputStream fileOutput = CodedOutputStream.newInstance(fileBytes);
        fileOutput.writeUInt32(1, 1);
        fileOutput.writeByteArray(2, storedBytes.toByteArray());
        fileOutput.flush();
        return fileBytes.toByteArray();
    }
}
