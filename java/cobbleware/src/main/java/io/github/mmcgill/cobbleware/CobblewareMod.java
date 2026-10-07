package io.github.mmcgill.cobbleware;

import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.block.WirelessObserverNetwork;
import io.github.mmcgill.cobbleware.command.CobblewareCommands;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.CobblewareConfigLoader;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.execution.ExecutionPoolManager;
import io.github.mmcgill.cobbleware.execution.ExecutionStateStore;
import io.github.mmcgill.cobbleware.execution.PlayerPriorityStore;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import io.github.mmcgill.cobbleware.polymer.CobblewarePolymer;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import io.github.mmcgill.cobbleware.registry.CobblewareItems;
import io.github.mmcgill.cobbleware.runtime.RuntimeManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.FullChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The common Fabric entrypoint for Cobbleware. */
public final class CobblewareMod implements ModInitializer {
    public static final String MOD_ID = "cobbleware";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ExecutionStateStore executionStateStore = new ExecutionStateStore(LOGGER);
    private static final PlayerPriorityStore playerPriorityStore = new PlayerPriorityStore(LOGGER);
    private static ExecutionDriver executionDriver;
    private static ExecutionPoolManager executionPoolManager;
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

    /** Returns the server-owned external execution-state store. */
    public static ExecutionStateStore executionStateStore() {
        return executionStateStore;
    }

    /** Returns the server-owned player execution-priority store. */
    public static PlayerPriorityStore playerPriorityStore() {
        return playerPriorityStore;
    }

    /** Returns the server-owned execution-pool assignment manager after Mod initialization. */
    public static ExecutionPoolManager executionPoolManager() {
        if (executionPoolManager == null) {
            throw new IllegalStateException("Cobbleware execution pool manager has not been initialized");
        }
        return executionPoolManager;
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

        CobblewareConfig configured = CobblewareConfigLoader.load(LOGGER);
        RuntimeManager runtimeManager = RuntimeManager.prepare(configured, LOGGER);
        CobblewareConfig config = runtimeManager.effectiveConfig();
        if (config.configurationError() == null && config.mainWorkerApiKey().isEmpty()) {
            LOGGER.error("Cobbleware mainWorkerApiKey is empty; authentication commands are disabled until it is"
                    + " configured");
        }
        MainWorkerClient mainWorkerClient = new MainWorkerClient(config, LOGGER);
        Instrumentation instrumentation = new Instrumentation(config.instrumentationWindowTicks());
        executionPoolManager = new ExecutionPoolManager(config.guaranteedPoolSize(), config.bestEffortPoolSize());
        executionDriver = new ExecutionDriver(
                mainWorkerClient,
                executionStateStore,
                instrumentation,
                playerPriorityStore,
                executionPoolManager,
                config.maxRunningExecutionsPerPlayer(),
                config.maxQueuedEventsPerPlayer(),
                config.maxPendingEffectsPerPlayer(),
                config.maxTotalExecutionStateBytesPerPlayer(),
                LOGGER);
        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, parameters) -> executionDriver.dispatchChatMessage(sender, message.signedContent()));
        wirelessObserverNetwork = new WirelessObserverNetwork();
        ServerLifecycleEvents.SERVER_STARTING.register(executionStateStore::initialize);
        ServerLifecycleEvents.SERVER_STARTING.register(playerPriorityStore::initialize);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            executionPoolManager.initialize(server.getPlayerList().getMaxPlayers());
            runtimeManager.start(
                    server, executionPoolManager.guaranteedPoolSize() + executionPoolManager.bestEffortPoolSize());
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
            executionStateStore.beforeSave();
            playerPriorityStore.beforeSave();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            executionStateStore.close();
            playerPriorityStore.close();
            mainWorkerClient.close();
            runtimeManager.close();
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
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            instrumentation.advanceTick();
            wirelessObserverNetwork.tick(server);
            executionDriver.refreshExecutionPools(server);
            executionDriver.driveExecutions(ExecutionDriver.minecraft(server));
        });
        CobblewareCommands.register(
                mainWorkerClient, instrumentation, executionDriver, playerPriorityStore, executionPoolManager);
    }
}
