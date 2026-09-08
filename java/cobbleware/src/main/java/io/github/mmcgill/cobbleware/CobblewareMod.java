package io.github.mmcgill.cobbleware;

import io.github.mmcgill.cobbleware.command.CobblewareCommands;
import net.fabricmc.api.ModInitializer;

/** The common Fabric entrypoint for Cobbleware. */
public final class CobblewareMod implements ModInitializer {
    public static final String MOD_ID = "cobbleware";

    @Override
    public void onInitialize() {
        CobblewareCommands.register();
    }
}
