package io.github.mmcgill.cobbleware.runtime;

import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.RuntimeMode;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Stages and owns the Runtime process for one Minecraft server lifetime. */
public final class RuntimeManager implements AutoCloseable {
    private static final int API_KEY_BYTES = 32;
    private static final int WORKER_POOL_RESERVE = 2;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private final CobblewareConfig configured;
    private final CobblewareConfig effective;
    private final RuntimeDistribution distribution;
    private final Logger logger;
    private final boolean stagesArtifacts;
    private final boolean managesProcess;
    private Process process;

    private RuntimeManager(
            CobblewareConfig configured,
            CobblewareConfig effective,
            RuntimeDistribution distribution,
            Logger logger,
            boolean stagesArtifacts,
            boolean managesProcess) {
        this.configured = configured;
        this.effective = effective;
        this.distribution = distribution;
        this.logger = logger;
        this.stagesArtifacts = stagesArtifacts;
        this.managesProcess = managesProcess;
    }

    /** Prepares the effective Mod connection before Main Worker clients are constructed. */
    public static RuntimeManager prepare(CobblewareConfig config, Logger logger) {
        RuntimeDistribution distribution = new RuntimeDistribution(RuntimeManager.class.getClassLoader());
        boolean bundled = distribution.isBundled();
        boolean stagesArtifacts = config.configurationError() == null
                && (config.runtimeMode() == RuntimeMode.MANAGED
                        || config.runtimeMode() == RuntimeMode.EXTRACT_ONLY
                        || (config.runtimeMode() == RuntimeMode.AUTO && bundled));
        boolean managesProcess = stagesArtifacts && config.runtimeMode() != RuntimeMode.EXTRACT_ONLY;
        CobblewareConfig effective = config;
        if (managesProcess) {
            if (!config.mainWorkerApiKey().isEmpty()) {
                logger.warn(
                        "Cobbleware mainWorkerApiKey is ignored because this server starts a managed Runtime with a per-start API key");
            }
            String apiKey = newApiKey();
            URI localRuntime = URI.create("http://127.0.0.1:" + config.runtimePort() + "/");
            effective = config.withMainWorkerConnection(localRuntime, apiKey);
        }
        return new RuntimeManager(config, effective, distribution, logger, stagesArtifacts, managesProcess);
    }

    /** Returns the immutable Main Worker configuration selected for this server lifetime. */
    public CobblewareConfig effectiveConfig() {
        return effective;
    }

    /** Stages and starts the owned Runtime after execution-pool capacity is known. */
    public synchronized void start(MinecraftServer server, int executionPoolCapacity) {
        if (!stagesArtifacts || process != null) {
            return;
        }
        try {
            RuntimeDistribution.Artifacts artifacts = distribution.stage(server.getWorldPath(LevelResource.ROOT));
            if (!managesProcess) {
                logger.info("Staged Cobbleware Runtime artifacts without starting a process");
                return;
            }
            ProcessBuilder builder = new ProcessBuilder(runtimeCommand(artifacts, configured.runtimePort()));
            builder.redirectErrorStream(true);
            builder.environment()
                    .put(
                            "COBBLEWARE_GAME_DIR",
                            server.getWorldPath(LevelResource.ROOT).toString());
            builder.environment().put("COBBLEWARE_API_KEY", effective.mainWorkerApiKey());
            builder.environment()
                    .put(
                            "COBBLEWARE_MAIN_WORKER_URL",
                            effective.mainWorkerBaseUri().toString());
            builder.environment()
                    .put(
                            "EDGE_RUNTIME_WORKER_POOL_SIZE",
                            Integer.toString(executionPoolCapacity + WORKER_POOL_RESERVE));
            process = builder.start();
            consumeOutput(process);
            process.onExit().thenAccept(this::reportUnexpectedExit);
            logger.info("Started Cobbleware managed Runtime on loopback port {}", configured.runtimePort());
        } catch (IOException | RuntimeException exception) {
            String prefix =
                    configured.runtimeMode() == RuntimeMode.AUTO ? "Could not start optional" : "Could not start";
            logger.error("{} Cobbleware managed Runtime: {}", prefix, exception.getMessage());
        }
    }

    private synchronized void reportUnexpectedExit(Process exited) {
        if (process == exited) {
            process = null;
            logger.warn("Cobbleware managed Runtime exited with status {}", exited.exitValue());
        }
    }

    static List<String> runtimeCommand(RuntimeDistribution.Artifacts artifacts, int runtimePort) {
        return List.of(
                artifacts.binary().toString(),
                "start",
                "--ip",
                "127.0.0.1",
                "--port",
                Integer.toString(runtimePort),
                "--main-service",
                artifacts.mainWorker().toString(),
                "--main-entrypoint",
                "file:///src/index.ts",
                "--event-worker",
                artifacts.eventWorker().toString(),
                "--events-entrypoint",
                "file:///src/index.ts",
                "--policy",
                "per_worker",
                "--max-parallelism",
                "1");
    }

    private void consumeOutput(Process running) {
        Thread thread = new Thread(
                () -> {
                    try (BufferedReader reader = running.inputReader(StandardCharsets.UTF_8)) {
                        for (String line; (line = reader.readLine()) != null; ) {
                            logger.info("Cobbleware Runtime: {}", line);
                        }
                    } catch (IOException exception) {
                        logger.debug("Could not read Cobbleware Runtime output: {}", exception.toString());
                    }
                },
                "Cobbleware Runtime output");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        Process running = process;
        process = null;
        running.destroy();
        try {
            if (!running.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warn("Cobbleware Runtime did not exit gracefully; terminating it forcibly");
                running.destroyForcibly();
                running.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.destroyForcibly();
        }
    }

    private static String newApiKey() {
        byte[] bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
