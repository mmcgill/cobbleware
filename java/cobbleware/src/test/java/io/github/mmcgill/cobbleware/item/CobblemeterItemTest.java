package io.github.mmcgill.cobbleware.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mmcgill.cobbleware.block.CobbleputerState;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CobblemeterItemTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void permitsAnyPlayerToPairWithUninitializedCobbleputers() {
        assertTrue(CobblemeterItem.canPairWith(CobbleputerState.UNINITIALIZED, null, null, false));
    }

    @Test
    void permitsExecutionOwnersToPairWithActiveCobbleputers() {
        UUID owner = UUID.randomUUID();

        for (CobbleputerState state : activeStates()) {
            assertTrue(CobblemeterItem.canPairWith(state, owner, owner, false));
        }
    }

    @Test
    void rejectsOtherNonPrivilegedPlayersForActiveCobbleputers() {
        UUID owner = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        for (CobbleputerState state : activeStates()) {
            assertFalse(CobblemeterItem.canPairWith(state, owner, otherPlayer, false));
        }
    }

    @Test
    void permitsGamemastersToPairWithAnyActiveCobbleputer() {
        UUID owner = UUID.randomUUID();
        UUID gamemaster = UUID.randomUUID();

        for (CobbleputerState state : activeStates()) {
            assertTrue(CobblemeterItem.canPairWith(state, owner, gamemaster, true));
        }
    }

    @Test
    void rejectsActiveCobbleputersWithoutAnOwnerOrPlayer() {
        UUID owner = UUID.randomUUID();

        for (CobbleputerState state : activeStates()) {
            assertFalse(CobblemeterItem.canPairWith(state, null, UUID.randomUUID(), false));
            assertFalse(CobblemeterItem.canPairWith(state, owner, null, false));
        }
    }

    private static List<CobbleputerState> activeStates() {
        return List.of(CobbleputerState.RUNNING, CobbleputerState.STOPPED, CobbleputerState.CRASHED);
    }
}
