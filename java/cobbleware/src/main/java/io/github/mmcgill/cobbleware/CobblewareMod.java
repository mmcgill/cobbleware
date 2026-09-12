package io.github.mmcgill.cobbleware;

import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.command.CobblewareCommands;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.CobblewareConfigLoader;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverNetwork;
import io.github.mmcgill.cobbleware.polymer.CobblewarePolymer;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import io.github.mmcgill.cobbleware.registry.CobblewareItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The common Fabric entrypoint for Cobbleware. */
public final class CobblewareMod implements ModInitializer {
    public static final String MOD_ID = "cobbleware";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static ExecutionDriver executionDriver;
    private static WirelessObserverNetwork wirelessObserverNetwork;

    /** Returns the server-owned execution driver after Mod initialization. */
    public static ExecutionDriver executionDriver() {
        if (executionDriver == null) {
            throw new IllegalStateException("Cobbleware ExecutionDriver has not been initialized");
        }
        return executionDriver;
    }

    /** Returns the driver when common initialization has completed, otherwise {@code null}. */
    public static ExecutionDriver executionDriverOrNull() {
        return executionDriver;
    }

    /** Returns the loaded-observer index after Mod initialization, otherwise {@code null}. */
    public static WirelessObserverNetwork wirelessObserverNetworkOrNull() {
        return wirelessObserverNetwork;
    }

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
        MainWorkerClient mainWorkerClient = new MainWorkerClient(config, LOGGER);
        executionDriver = new ExecutionDriver(
                mainWorkerClient, new Instrumentation(config.instrumentationWindowTicks()), LOGGER);
        wirelessObserverNetwork = new WirelessObserverNetwork();
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> chunk.getBlockEntities().values().forEach(blockEntity -> {
            if (blockEntity instanceof io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity cobbleputer) {
                executionDriver.activate(cobbleputer);
            }
            if (blockEntity instanceof WirelessObserverBlockEntity observer) {
                wirelessObserverNetwork.activate(observer);
            }
        }));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> chunk.getBlockEntities().values().forEach(blockEntity -> {
            if (blockEntity instanceof io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity cobbleputer) {
                executionDriver.deactivate(cobbleputer);
            }
            if (blockEntity instanceof WirelessObserverBlockEntity observer) {
                wirelessObserverNetwork.deactivate(observer);
            }
        }));
        CobblewareCommands.register(mainWorkerClient, executionDriver);
    }
}
