package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

class WirelessObserverPolymerModelsTest {
    @Test
    void mapsEveryDyeColorToItsDedicatedModel() {
        for (DyeColor color : DyeColor.values()) {
            assertEquals(
                    Identifier.fromNamespaceAndPath("cobbleware", "block/" + color.getName() + "_wireless_observer"),
                    WirelessObserverPolymerModels.modelIdFor(color));
        }
    }
}
