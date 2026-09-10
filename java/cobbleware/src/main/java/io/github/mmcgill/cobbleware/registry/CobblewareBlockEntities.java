package io.github.mmcgill.cobbleware.registry;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Registers Cobbleware block entities. */
public final class CobblewareBlockEntities {
    public static final BlockEntityType<CobbleputerBlockEntity> COBBLEPUTER;
    public static final BlockEntityType<WirelessObserverBlockEntity> WIRELESS_OBSERVER;
    private static final ResourceKey<BlockEntityType<?>> COBBLEPUTER_KEY = ResourceKey.create(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobbleputer"));
    private static final ResourceKey<BlockEntityType<?>> WIRELESS_OBSERVER_KEY = ResourceKey.create(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "wireless_observer"));

    static {
        COBBLEPUTER = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                COBBLEPUTER_KEY,
                new BlockEntityType<>(CobbleputerBlockEntity::new, Set.of(CobblewareBlocks.COBBLEPUTER)));
        PolymerBlockUtils.registerBlockEntity(COBBLEPUTER);
        WIRELESS_OBSERVER = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                WIRELESS_OBSERVER_KEY,
                new BlockEntityType<>(WirelessObserverBlockEntity::new, Set.copyOf(CobblewareBlocks.wirelessObservers())));
        PolymerBlockUtils.registerBlockEntity(WIRELESS_OBSERVER);
    }

    private CobblewareBlockEntities() {
    }

    public static void initialize() {
        // Loads this class and performs its static registrations.
    }
}
