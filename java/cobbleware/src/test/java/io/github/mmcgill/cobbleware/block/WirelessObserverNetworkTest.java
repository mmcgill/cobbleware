package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

class WirelessObserverNetworkTest {
    private static final UUID OBSERVER_ID = new UUID(1, 2);
    private static final UUID OTHER_OBSERVER_ID = new UUID(3, 4);

    @Test
    void independentlyIntersectsOptionalRedstonePowerSelectors() {
        assertTrue(matches(Optional.empty(), Optional.empty()));
        assertTrue(matches(Optional.of(OBSERVER_ID), Optional.empty()));
        assertFalse(matches(Optional.of(OTHER_OBSERVER_ID), Optional.empty()));
        assertTrue(matches(Optional.empty(), Optional.of(DyeColor.RED)));
        assertFalse(matches(Optional.empty(), Optional.of(DyeColor.BLUE)));
        assertTrue(matches(Optional.of(OBSERVER_ID), Optional.of(DyeColor.RED)));
        assertFalse(matches(Optional.of(OBSERVER_ID), Optional.of(DyeColor.BLUE)));
        assertFalse(matches(Optional.of(OTHER_OBSERVER_ID), Optional.of(DyeColor.RED)));
    }

    private static boolean matches(Optional<UUID> observerId, Optional<DyeColor> observerColor) {
        return WirelessObserverNetwork.matchesRedstonePowerTarget(
                OBSERVER_ID, DyeColor.RED, observerId, observerColor);
    }
}
