package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cobbleware.internal.PlayerExecutionStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionStateStoreTest {
    @Test
    void roundTripsActiveAndCrashedExecutionRecords() throws Exception {
        UUID activeCobbleputer = UUID.randomUUID();
        UUID crashedCobbleputer = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        byte[] activeState = {1, 2, 3};
        byte[] crashedState = {4, 5};
        Map<UUID, ExecutionStateStore.ExecutionRecord> records = Map.of(
                activeCobbleputer,
                new ExecutionStateStore.ExecutionRecord(execution, "active", activeState, 8, 3, 42L, true),
                crashedCobbleputer,
                new ExecutionStateStore.ExecutionRecord(null, "crashed", crashedState, 1, 0, null, false));

        Map<UUID, ExecutionStateStore.ExecutionRecord> decoded =
                ExecutionStateStore.decode(ExecutionStateStore.encode(records));

        assertEquals(execution, decoded.get(activeCobbleputer).executionId());
        assertEquals("active", decoded.get(activeCobbleputer).ownerPlayerName());
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
    void executionRecordDefensivelyCopiesStateBytes() {
        byte[] original = {1, 2};
        ExecutionStateStore.ExecutionRecord record =
                new ExecutionStateStore.ExecutionRecord(UUID.randomUUID(), "owner", original, 1, 0, null, false);
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
}
