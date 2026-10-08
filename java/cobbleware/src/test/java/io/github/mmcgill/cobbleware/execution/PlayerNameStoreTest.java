package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParseException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerNameStoreTest {
    @Test
    void roundTripsPlayerNamesInStableJson() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, String> names = Map.of(second, "Second", first, "First");

        String encoded = PlayerNameStore.encode(names);

        assertEquals("""
                {
                  "formatVersion": 1,
                  "players": {
                    "00000000-0000-0000-0000-000000000001": "First",
                    "00000000-0000-0000-0000-000000000002": "Second"
                  }
                }
                """, encoded);
        assertEquals(names, PlayerNameStore.decode(encoded));
    }

    @Test
    void rejectsInvalidPlayerNameFiles() {
        assertThrows(JsonParseException.class, () -> PlayerNameStore.decode("{\"formatVersion\":2,\"players\":{}}"));
        assertThrows(
                JsonParseException.class,
                () -> PlayerNameStore.decode("{\"formatVersion\":1,\"players\":{\"not-a-uuid\":\"name\"}}"));
        assertThrows(
                JsonParseException.class,
                () -> PlayerNameStore.decode(
                        "{\"formatVersion\":1,\"players\":{\"00000000-0000-0000-0000-000000000001\":\"\"}}"));
    }
}
