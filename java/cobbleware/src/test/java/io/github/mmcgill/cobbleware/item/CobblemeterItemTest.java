package io.github.mmcgill.cobbleware.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mmcgill.cobbleware.block.CobbleputerState;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CobblemeterItemTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void permitsCobbleputerOwnersToPairWithTheirCobbleputers() {
        UUID owner = UUID.randomUUID();

        assertTrue(CobblemeterItem.canPairWith(owner, owner, false));
    }

    @Test
    void rejectsOtherNonPrivilegedPlayers() {
        UUID owner = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        assertFalse(CobblemeterItem.canPairWith(owner, otherPlayer, false));
    }

    @Test
    void permitsGamemastersToPairWithAnyCobbleputer() {
        UUID owner = UUID.randomUUID();
        UUID gamemaster = UUID.randomUUID();

        assertTrue(CobblemeterItem.canPairWith(owner, gamemaster, true));
    }

    @Test
    void rejectsNonPrivilegedPairingWhenTheCobbleputerOrPlayerHasNoOwner() {
        UUID owner = UUID.randomUUID();

        assertFalse(CobblemeterItem.canPairWith(null, UUID.randomUUID(), false));
        assertFalse(CobblemeterItem.canPairWith(owner, null, false));
    }

    @Test
    void permitsOwnersAndGamemastersToUnpairObservers() {
        UUID owner = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        assertTrue(CobblemeterItem.canUnpairFrom(owner, owner, false));
        assertTrue(CobblemeterItem.canUnpairFrom(owner, otherPlayer, true));
    }

    @Test
    void rejectsNonOwnersFromUnpairingObservers() {
        UUID owner = UUID.randomUUID();

        assertFalse(CobblemeterItem.canUnpairFrom(owner, UUID.randomUUID(), false));
        assertFalse(CobblemeterItem.canUnpairFrom(owner, null, false));
        assertFalse(CobblemeterItem.canUnpairFrom(null, UUID.randomUUID(), false));
    }

    @Test
    void permitsObserverPairingAtOrBelowTheRangeLimit() {
        GlobalPos target = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);

        assertTrue(CobblemeterItem.isWithinObserverPairingRange(target, target));
        assertTrue(CobblemeterItem.isWithinObserverPairingRange(
                GlobalPos.of(Level.OVERWORLD, new BlockPos(CobblemeterItem.OBSERVER_PAIRING_RANGE_BLOCKS, 0, 0)),
                target));
    }

    @Test
    void rejectsObserverPairingBeyondTheRangeLimit() {
        GlobalPos target = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);

        assertFalse(CobblemeterItem.isWithinObserverPairingRange(
                GlobalPos.of(Level.OVERWORLD, new BlockPos(CobblemeterItem.OBSERVER_PAIRING_RANGE_BLOCKS + 1, 0, 0)),
                target));
        assertFalse(CobblemeterItem.isWithinObserverPairingRange(
                GlobalPos.of(Level.OVERWORLD, new BlockPos(46, 46, 0)), target));
    }

    @Test
    void rejectsObserverPairingAcrossDimensions() {
        assertFalse(CobblemeterItem.isWithinObserverPairingRange(
                GlobalPos.of(Level.NETHER, BlockPos.ZERO), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)));
    }

    private static List<CobbleputerState> activeStates() {
        return List.of(CobbleputerState.RUNNING, CobbleputerState.STOPPED, CobbleputerState.CRASHED);
    }
}
