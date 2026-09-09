package io.github.mmcgill.cobbleware.registry;

import io.github.mmcgill.cobbleware.CobblewareMod;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Registers Cobbleware items. */
public final class CobblewareItems {
    public static final Item COBBLEPUTER;
    private static final ResourceKey<Item> COBBLEPUTER_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobbleputer"));

    static {
        COBBLEPUTER = register(
                COBBLEPUTER_KEY,
                new PolymerBlockItem(CobblewareBlocks.COBBLEPUTER, new Item.Properties().setId(COBBLEPUTER_KEY)));
    }

    private CobblewareItems() {
    }

    public static void initialize() {
        // Loads this class and performs its static registrations.
    }

    private static Item register(ResourceKey<Item> key, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}
