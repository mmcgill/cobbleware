package io.github.mmcgill.cobbleware.polymer;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import io.github.mmcgill.cobbleware.CobblewareMod;

/** Configures Cobbleware's required generated resource pack. */
public final class CobblewarePolymer {
    private CobblewarePolymer() {
    }

    public static void initialize() {
        if (!PolymerResourcePackUtils.addModAssets(CobblewareMod.MOD_ID)) {
            throw new IllegalStateException("Could not register Cobbleware assets with Polymer's resource pack");
        }
        PolymerResourcePackUtils.markAsRequired();
    }
}
