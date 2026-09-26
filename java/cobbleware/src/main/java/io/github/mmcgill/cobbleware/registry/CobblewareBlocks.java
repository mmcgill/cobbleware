package io.github.mmcgill.cobbleware.registry;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerPolymerModels;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlock;
import io.github.mmcgill.cobbleware.block.WirelessObserverPolymerModels;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Registers Cobbleware blocks. */
public final class CobblewareBlocks {
    public static final Block COBBLEPUTER;
    private static final Map<DyeColor, WirelessObserverBlock> WIRELESS_OBSERVERS;
    private static final ResourceKey<Block> COBBLEPUTER_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobbleputer"));

    static {
        CobbleputerPolymerModels.initialize();
        WirelessObserverPolymerModels.initialize();
        COBBLEPUTER = register(
                COBBLEPUTER_KEY,
                new CobbleputerBlock(BlockBehaviour.Properties.of()
                        .setId(COBBLEPUTER_KEY)
                        .strength(3.0F, 6.0F)
                        .sound(SoundType.METAL)));

        Map<DyeColor, WirelessObserverBlock> observers = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            ResourceKey<Block> key = ResourceKey.create(
                    Registries.BLOCK,
                    Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, color.getName() + "_wireless_observer"));
            WirelessObserverBlock observer = (WirelessObserverBlock) register(
                    key,
                    new WirelessObserverBlock(
                            color,
                            BlockBehaviour.Properties.of()
                                    .setId(key)
                                    .strength(3.0F, 6.0F)
                                    .sound(SoundType.METAL)));
            observers.put(color, observer);
        }
        WIRELESS_OBSERVERS = Map.copyOf(observers);
    }

    private CobblewareBlocks() {}

    public static void initialize() {
        // Loads this class and performs its static registrations.
    }

    public static WirelessObserverBlock wirelessObserver(DyeColor color) {
        return WIRELESS_OBSERVERS.get(color);
    }

    public static Collection<WirelessObserverBlock> wirelessObservers() {
        return WIRELESS_OBSERVERS.values();
    }

    public static boolean isWirelessObserver(BlockState state) {
        return state.getBlock() instanceof WirelessObserverBlock;
    }

    private static Block register(ResourceKey<Block> key, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }
}
