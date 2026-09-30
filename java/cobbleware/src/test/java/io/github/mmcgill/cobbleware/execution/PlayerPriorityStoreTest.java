package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerPriorityStoreTest {
    @Test
    void roundTripsPriorityRecordsInStableJson() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, PlayerPriorityStore.Priorities> priorities = Map.of(
                second, new PlayerPriorityStore.Priorities(3, 1), first, new PlayerPriorityStore.Priorities(1, 2));

        String encoded = PlayerPriorityStore.encode(priorities);

        assertEquals("""
                {
                  "formatVersion": 1,
                  "players": {
                    "00000000-0000-0000-0000-000000000001": {
                      "onlinePriority": 1,
                      "offlinePriority": 2
                    },
                    "00000000-0000-0000-0000-000000000002": {
                      "onlinePriority": 3,
                      "offlinePriority": 1
                    }
                  }
                }
                """, encoded);
        assertEquals(priorities, PlayerPriorityStore.decode(encoded));
    }

    @Test
    void rejectsInvalidPriorityFiles() {
        assertThrows(
                JsonParseException.class, () -> PlayerPriorityStore.decode("{\"formatVersion\":2,\"players\":{}}"));
        assertThrows(
                JsonParseException.class,
                () -> PlayerPriorityStore.decode("{\"formatVersion\":1,\"players\":{\"not-a-uuid\":{}}}"));
        assertThrows(
                JsonParseException.class,
                () -> PlayerPriorityStore.decode(
                        "{\"formatVersion\":1,\"players\":{\"00000000-0000-0000-0000-000000000001\":{"
                                + "\"onlinePriority\":1,\"offlinePriority\":4}}}"));
        assertThrows(
                JsonParseException.class,
                () -> PlayerPriorityStore.decode(
                        "{\"formatVersion\":1,\"players\":{\"00000000-0000-0000-0000-000000000001\":{"
                                + "\"onlinePriority\":1}}}"));
    }

    @Test
    void priorityValuesAreLimitedToTheDocumentedRange() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerPriorityStore.Priorities(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPriorityStore.Priorities(1, 4));
    }

    @Test
    void atomicallyPersistsPriorityJson(@TempDir Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        Map<UUID, PlayerPriorityStore.Priorities> priorities = Map.of(player, new PlayerPriorityStore.Priorities(1, 3));
        Path file = directory.resolve("priorities.json");

        ExecutionStateStore.writeAtomically(
                file, PlayerPriorityStore.encode(priorities).getBytes(StandardCharsets.UTF_8));

        assertEquals(priorities, PlayerPriorityStore.decode(Files.readString(file, StandardCharsets.UTF_8)));
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count());
        }
    }
}
