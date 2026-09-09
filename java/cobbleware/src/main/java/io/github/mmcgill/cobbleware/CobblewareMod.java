package io.github.mmcgill.cobbleware;

import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.command.CobblewareCommands;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.CobblewareConfigLoader;
import io.github.mmcgill.cobbleware.polymer.CobblewarePolymer;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import io.github.mmcgill.cobbleware.registry.CobblewareItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The common Fabric entrypoint for Cobbleware. */
public final class CobblewareMod implements ModInitializer {
    public static final String MOD_ID = "cobbleware";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CobblewarePolymer.initialize();
        CobblewareBlocks.initialize();
        CobblewareBlockEntities.initialize();
        CobblewareItems.initialize();

        CobblewareConfig config = CobblewareConfigLoader.load(LOGGER);
        if (config.configurationError() == null && config.mainWorkerApiKey().isEmpty()) {
            LOGGER.error("Cobbleware mainWorkerApiKey is empty; authentication commands are disabled until it is configured");
        }
        CobblewareCommands.register(new MainWorkerClient(config, LOGGER));
    }
}
