package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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

    @Test
    void matchesOnlyTheTargetChunkInItsOwnDimension() {
        ResourceKey<Level> overworld = Level.OVERWORLD;
        ResourceKey<Level> nether = Level.NETHER;
        GlobalPos target = GlobalPos.of(overworld, new BlockPos(33, 64, -17));

        assertTrue(WirelessObserverNetwork.targetIsInChunk(target, overworld, new ChunkPos(2, -2)));
        assertFalse(WirelessObserverNetwork.targetIsInChunk(target, overworld, new ChunkPos(1, -2)));
        assertFalse(WirelessObserverNetwork.targetIsInChunk(target, nether, new ChunkPos(2, -2)));
    }

    @Test
    void assignsPathsToDeterministicVisualizationTickOffsets() {
        UUID observerId = new UUID(0, 5);
        int offset = WirelessObserverNetwork.visualizationTickOffset(observerId);

        assertTrue(offset >= 0 && offset < 8);
        assertTrue(WirelessObserverNetwork.shouldDrawPath(observerId, offset));
        assertTrue(WirelessObserverNetwork.shouldDrawPath(observerId, offset + 8));
        assertFalse(WirelessObserverNetwork.shouldDrawPath(observerId, offset + 1));
        assertTrue(offset == WirelessObserverNetwork.visualizationTickOffset(observerId));
    }

    @Test
    void movesParticlesAtTheSameWorldSpaceSpeedOnDifferentLengthPaths() {
        Vec3 observer = Vec3.ZERO;
        Vec3 shortTarget = new Vec3(10, 0, 0);
        Vec3 longTarget = new Vec3(20, 0, 0);

        assertEquals(new Vec3(1, 0, 0), WirelessObserverNetwork.particlePosition(observer, shortTarget, 8));
        assertEquals(new Vec3(1, 0, 0), WirelessObserverNetwork.particlePosition(observer, longTarget, 8));
    }

    @Test
    void wrapsParticlePositionBackToObserver() {
        Vec3 observer = Vec3.ZERO;
        Vec3 target = new Vec3(1, 0, 0);

        assertEquals(new Vec3(0.875, 0, 0), WirelessObserverNetwork.particlePosition(observer, target, 7));
        assertEquals(observer, WirelessObserverNetwork.particlePosition(observer, target, 8));
    }

    @Test
    void leavesZeroLengthPathsAtTheirOnlyPosition() {
        Vec3 position = new Vec3(2, 3, 4);

        assertEquals(position, WirelessObserverNetwork.particlePosition(position, position, 123));
    }

    private static boolean matches(Optional<UUID> observerId, Optional<DyeColor> observerColor) {
        return WirelessObserverNetwork.matchesRedstonePowerTarget(OBSERVER_ID, DyeColor.RED, observerId, observerColor);
    }
}
