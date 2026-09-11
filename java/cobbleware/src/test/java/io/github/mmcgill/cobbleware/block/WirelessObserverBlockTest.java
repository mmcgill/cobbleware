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
    void clampsUnsignedPowerAndOnlyEmitsWeaklyInItsFacingDirection() {
        assertEquals(15, WirelessObserverBlock.clampedPower(16));
        assertEquals(15, WirelessObserverBlock.clampedPower(-1));
        assertEquals(7, WirelessObserverBlock.weakPowerFor(Direction.EAST, 7, Direction.WEST));
        for (Direction direction : Direction.values()) {
            if (direction != Direction.WEST) {
                assertEquals(0, WirelessObserverBlock.weakPowerFor(Direction.EAST, 7, direction));
            }
        }
    }
}
