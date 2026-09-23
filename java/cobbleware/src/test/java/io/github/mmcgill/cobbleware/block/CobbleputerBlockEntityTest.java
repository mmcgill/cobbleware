package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CobbleputerBlockEntityTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identifiesOnlyCompleteCrashedRecordsAsRecoverable() {
        UUID owner = UUID.randomUUID();
        byte[] state = new byte[] {1, 2, 3};

        assertTrue(CobbleputerBlockEntity.isRecoverableCrashedExecution(null, owner, "owner", state));
        assertFalse(CobbleputerBlockEntity.isRecoverableCrashedExecution(UUID.randomUUID(), owner, "owner", state));
        assertFalse(CobbleputerBlockEntity.isRecoverableCrashedExecution(null, null, "owner", state));
        assertFalse(CobbleputerBlockEntity.isRecoverableCrashedExecution(null, owner, "", state));
        assertFalse(CobbleputerBlockEntity.isRecoverableCrashedExecution(null, owner, "owner", null));
    }
}
