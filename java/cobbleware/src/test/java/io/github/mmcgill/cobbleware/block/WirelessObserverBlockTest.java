package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WirelessObserverBlockTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void facesTheBlockInEveryVanillaObserverPlacementDirection() {
        for (Direction direction : Direction.values()) {
            assertEquals(direction, WirelessObserverBlock.facingForPlacement(direction));
        }
    }

    @Test
    void clampsUnsignedPowerAndOnlyEmitsWeaklyOutItsBack() {
        assertEquals(15, WirelessObserverBlock.clampedPower(16));
        assertEquals(15, WirelessObserverBlock.clampedPower(-1));
        for (Direction facing : Direction.values()) {
            for (Direction direction : Direction.values()) {
                int expectedPower = direction == facing ? 7 : 0;
                assertEquals(expectedPower, WirelessObserverBlock.weakPowerFor(facing, 7, direction));
            }
        }
    }
}
