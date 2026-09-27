package io.github.mmcgill.cobbleware;

import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverNetwork;
import io.github.mmcgill.cobbleware.command.CobblewareCommands;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.CobblewareConfigLoader;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.execution.ExecutionStateStore;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import io.github.mmcgill.cobbleware.polymer.CobblewarePolymer;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import io.github.mmcgill.cobbleware.registry.CobblewareItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The common Fabric entrypoint for Cobbleware. */
public final class CobblewareMod implements ModInitializer {
    public static final String MOD_ID = "cobbleware";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static ExecutionDriver executionDriver;
    private static ExecutionStateStore executionStateStore;
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

    /** Returns the server-owned external execution-state store, or {@code null} before server startup. */
    public static ExecutionStateStore executionStateStoreOrNull() {
        return executionStateStore;
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
            LOGGER.error("Cobbleware mainWorkerApiKey is empty; authentication commands are disabled until it is"
                    + " configured");
        }
        MainWorkerClient mainWorkerClient = new MainWorkerClient(config, LOGGER);
        executionDriver = new ExecutionDriver(
                mainWorkerClient,
                new Instrumentation(config.instrumentationWindowTicks()),
                config.maxRunningExecutionsPerPlayer(),
                LOGGER);
        wirelessObserverNetwork = new WirelessObserverNetwork();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            executionStateStore = new ExecutionStateStore(server.getWorldPath(LevelResource.ROOT), server, LOGGER);
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
            if (executionStateStore != null) {
                executionStateStore.beforeSave();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (executionStateStore != null) {
                executionStateStore.close();
                executionStateStore = null;
            }
            mainWorkerClient.close();
        });
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, previousStatus, nextStatus) -> {
            boolean wasBlockTicking = previousStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING);
            boolean isBlockTicking = nextStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING);
            if (wasBlockTicking && !isBlockTicking) {
                chunk.getBlockEntities().values().forEach(blockEntity -> {
                    if (blockEntity instanceof CobbleputerBlockEntity cobbleputer) {
                        executionDriver.deactivateForChunkBecameNonTicking(cobbleputer);
                    }
                    if (blockEntity instanceof WirelessObserverBlockEntity observer) {
                        wirelessObserverNetwork.deactivate(observer);
                    }
                });
            } else if (!wasBlockTicking && isBlockTicking) {
                chunk.getBlockEntities().values().forEach(blockEntity -> {
                    if (blockEntity instanceof CobbleputerBlockEntity cobbleputer) {
                        executionDriver.activate(cobbleputer);
                    }
                    if (blockEntity instanceof WirelessObserverBlockEntity observer) {
                        wirelessObserverNetwork.activate(observer);
                    }
                });
                wirelessObserverNetwork.targetChunkLoaded(level, chunk.getPos());
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> chunk.getBlockEntities()
                .values()
                .forEach(blockEntity -> {
                    if (blockEntity instanceof CobbleputerBlockEntity cobbleputer) {
                        executionDriver.deactivate(cobbleputer);
                    }
                    if (blockEntity instanceof WirelessObserverBlockEntity observer) {
                        wirelessObserverNetwork.deactivate(observer);
                    }
                }));
        CobblewareCommands.register(mainWorkerClient, executionDriver);
    }
}
