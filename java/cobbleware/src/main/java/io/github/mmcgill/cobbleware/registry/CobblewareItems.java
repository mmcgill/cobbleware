package io.github.mmcgill.cobbleware.registry;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.item.CobblemeterItem;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlock;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;

/** Registers Cobbleware items. */
public final class CobblewareItems {
    public static final Item COBBLEPUTER;
    public static final Item COBBLEMETER;
    private static final Map<DyeColor, Item> WIRELESS_OBSERVERS;
    private static final ResourceKey<Item> COBBLEPUTER_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobbleputer"));
    private static final ResourceKey<Item> COBBLEMETER_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "cobblemeter"));

    static {
        COBBLEPUTER = register(
                COBBLEPUTER_KEY,
                new PolymerBlockItem(CobblewareBlocks.COBBLEPUTER, new Item.Properties().setId(COBBLEPUTER_KEY)));
        COBBLEMETER = register(
                COBBLEMETER_KEY,
                new CobblemeterItem(new Item.Properties().setId(COBBLEMETER_KEY).stacksTo(1)));

        Map<DyeColor, Item> observers = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            ResourceKey<Item> key = ResourceKey.create(
                    Registries.ITEM,
                    Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, color.getName() + "_wireless_observer"));
            WirelessObserverBlock block = CobblewareBlocks.wirelessObserver(color);
            observers.put(color, register(key, new PolymerBlockItem(block, new Item.Properties().setId(key))));
        }
        WIRELESS_OBSERVERS = Map.copyOf(observers);
    }

    private CobblewareItems() {
    }

    public static void initialize() {
        // Loads this class and performs its static registrations.
    }

    public static Item wirelessObserver(DyeColor color) {
        return WIRELESS_OBSERVERS.get(color);
    }

    private static Item register(ResourceKey<Item> key, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}
