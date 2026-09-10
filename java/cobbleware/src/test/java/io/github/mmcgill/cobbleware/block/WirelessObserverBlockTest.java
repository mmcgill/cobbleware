package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class WirelessObserverBlockTest {
    @Test
    void facesTheBlockInEveryVanillaObserverPlacementDirection() {
        for (Direction direction : Direction.values()) {
            assertEquals(direction, WirelessObserverBlock.facingForPlacement(direction));
        }
    }
}
