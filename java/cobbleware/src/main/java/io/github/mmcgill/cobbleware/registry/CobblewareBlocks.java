package io.github.mmcgill.cobbleware.registry;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerPolymerModels;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Registers Cobbleware blocks. */
public final class CobblewareBlocks {
    public static final Block COBBLEPUTER;
    private static final ResourceKey<Block> COBBLEPUTER_KEY = ResourceKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobbleputer"));

    static {
        CobbleputerPolymerModels.initialize();
        COBBLEPUTER = register(
                COBBLEPUTER_KEY,
                new CobbleputerBlock(BlockBehaviour.Properties.of()
                        .setId(COBBLEPUTER_KEY)
                        .strength(3.0F, 6.0F)
                        .sound(SoundType.METAL)));
    }

    private CobblewareBlocks() {
    }

    public static void initialize() {
        // Loads this class and performs its static registrations.
    }

    private static Block register(ResourceKey<Block> key, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }
}
