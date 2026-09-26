package io.github.mmcgill.cobbleware.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CobblemeterPairingTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void newDataIsUnpaired() {
        assertTrue(CobblemeterPairing.getPairedCobbleputer(new CompoundTag()).isEmpty());
    }

    @Test
    void persistsAndRoundTripsTargetsInAnyDimension() {
        CompoundTag overworldData = new CompoundTag();
        GlobalPos overworldTarget = GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 72, -19));
        CobblemeterPairing.pair(overworldData, overworldTarget);

        assertEquals(
                overworldTarget,
                CobblemeterPairing.getPairedCobbleputer(overworldData).orElseThrow());
        assertEquals(
                overworldTarget,
                CobblemeterPairing.getPairedCobbleputer(overworldData.copy()).orElseThrow());

        CompoundTag netherData = new CompoundTag();
        GlobalPos netherTarget = GlobalPos.of(Level.NETHER, new BlockPos(-80, 48, 31));
        CobblemeterPairing.pair(netherData, netherTarget);

        assertEquals(
                netherTarget,
                CobblemeterPairing.getPairedCobbleputer(netherData).orElseThrow());
    }

    @Test
    void replacesAnExistingPairing() {
        CompoundTag data = new CompoundTag();
        CobblemeterPairing.pair(data, GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 72, -19)));
        GlobalPos replacementTarget = GlobalPos.of(Level.END, new BlockPos(-80, 48, 31));

        CobblemeterPairing.pair(data, replacementTarget);

        assertEquals(
                replacementTarget, CobblemeterPairing.getPairedCobbleputer(data).orElseThrow());
    }

    @Test
    void ignoresMissingOrMalformedPairingData() {
        CompoundTag malformedData = new CompoundTag();
        malformedData.putString(CobblemeterPairing.DIMENSION_KEY, "not a valid id");
        assertTrue(CobblemeterPairing.getPairedCobbleputer(malformedData).isEmpty());

        CompoundTag partialData = new CompoundTag();
        partialData.putLong(CobblemeterPairing.POSITION_KEY, new BlockPos(1, 2, 3).asLong());
        assertTrue(CobblemeterPairing.getPairedCobbleputer(partialData).isEmpty());
    }

    @Test
    void preservesUnrelatedCustomData() {
        CompoundTag data = new CompoundTag();
        data.putString("unrelated", "preserved");

        CobblemeterPairing.pair(data, GlobalPos.of(Level.END, new BlockPos(5, 6, 7)));

        assertEquals("preserved", data.getStringOr("unrelated", ""));
        assertFalse(CobblemeterPairing.getPairedCobbleputer(data).isEmpty());
    }

    @Test
    void recordsCanonicalDimensionIdentifiers() {
        CompoundTag data = new CompoundTag();
        ResourceKey<Level> customDimension =
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("example", "moon"));
        CobblemeterPairing.pair(data, GlobalPos.of(customDimension, BlockPos.ZERO));

        assertEquals("example:moon", data.getStringOr(CobblemeterPairing.DIMENSION_KEY, ""));
    }
}
