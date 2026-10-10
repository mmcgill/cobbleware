package io.github.mmcgill.cobbleware.runtime;

import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.RuntimeMode;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Stages and owns the Runtime process for one Minecraft server lifetime. */
public final class RuntimeManager implements AutoCloseable {
    private static final int API_KEY_BYTES = 32;
    private static final int WORKER_POOL_RESERVE = 2;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READY_RETRY_DELAY = Duration.ofMillis(100);
    private final CobblewareConfig configured;
    private final CobblewareConfig effective;
    private final RuntimeDistribution distribution;
    private final Logger logger;
    private final boolean managesProcess;
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Cobbleware Runtime startup");
        thread.setDaemon(true);
        return thread;
    });
    private State state;
    private boolean closing;
    private Future<?> startupTask;
    private Process process;

    private RuntimeManager(
            CobblewareConfig configured,
            CobblewareConfig effective,
            RuntimeDistribution distribution,
            Logger logger,
            boolean managesProcess) {
        this.configured = configured;
        this.effective = effective;
        this.distribution = distribution;
        this.logger = logger;
        this.managesProcess = managesProcess;
        state = managesProcess ? State.IDLE : State.EXTERNAL;
    }

    /** Prepares the effective Mod connection before Main Worker clients are constructed. */
    public static RuntimeManager prepare(CobblewareConfig config, Logger logger) {
        RuntimeDistribution distribution = new RuntimeDistribution(RuntimeManager.class.getClassLoader(), logger);
        boolean bundled = distribution.isBundled();
        boolean managesProcess = config.configurationError() == null
                && (config.runtimeMode() == RuntimeMode.MANAGED
                        || (config.runtimeMode() == RuntimeMode.AUTO && bundled));
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
        return new RuntimeManager(config, effective, distribution, logger, managesProcess);
    }

    /** Returns the immutable Main Worker configuration selected for this server lifetime. */
    public CobblewareConfig effectiveConfig() {
        return effective;
    }

    /** Starts asynchronous staging after execution-pool capacity is known. */
    public synchronized void start(MinecraftServer server, int executionPoolCapacity) {
        if (!managesProcess || closing || startupTask != null || process != null) {
            return;
        }
        state = State.STAGING;
        java.nio.file.Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        logger.info("Preparing Cobbleware Runtime in the background; the server will remain responsive");
        startupTask = startupExecutor.submit(() -> stageAndStart(worldRoot, executionPoolCapacity));
    }

    /** Whether the managed Runtime can accept execution requests. */
    public synchronized boolean isReadyForExecutions() {
        return !managesProcess || state == State.RUNNING;
    }

    /** A player-facing explanation for a currently unavailable managed Runtime. */
    public synchronized String unavailableReason() {
        return switch (state) {
            case STAGING -> "Cobbleware Runtime is downloading or staging; try again shortly.";
            case STARTING -> "Cobbleware Runtime is starting; try again shortly.";
            case FAILED -> "Cobbleware Runtime failed to start; see the server log for details.";
            case STOPPED -> "Cobbleware Runtime is shutting down.";
            default -> "Cobbleware Runtime is not ready yet; try again shortly.";
        };
    }

    private void stageAndStart(java.nio.file.Path worldRoot, int executionPoolCapacity) {
        try {
            RuntimeDistribution.Artifacts artifacts = distribution.stage(worldRoot);
            if (isClosing()) {
                return;
            }
            synchronized (this) {
                if (closing) {
                    return;
                }
                state = State.STARTING;
            }
            ProcessBuilder builder = new ProcessBuilder(runtimeCommand(artifacts, configured.runtimePort()));
            builder.redirectErrorStream(true);
            builder.environment().put("COBBLEWARE_GAME_DIR", worldRoot.toString());
            builder.environment().put("COBBLEWARE_API_KEY", effective.mainWorkerApiKey());
            builder.environment()
                    .put(
                            "COBBLEWARE_MAIN_WORKER_URL",
                            effective.mainWorkerBaseUri().toString());
            builder.environment()
                    .put(
                            "EDGE_RUNTIME_WORKER_POOL_SIZE",
                            Integer.toString(executionPoolCapacity + WORKER_POOL_RESERVE));
            Process started = builder.start();
            synchronized (this) {
                if (closing) {
                    started.destroyForcibly();
                    return;
                }
                process = started;
            }
            consumeOutput(started);
            started.onExit().thenAccept(this::reportUnexpectedExit);
            waitUntilReady(started);
            synchronized (this) {
                if (process != started || closing) {
                    return;
                }
                state = State.RUNNING;
            }
            logger.info("Cobbleware managed Runtime is ready on loopback port {}", configured.runtimePort());
        } catch (IOException | RuntimeException exception) {
            failStartup(exception);
        }
    }

    private synchronized void reportUnexpectedExit(Process exited) {
        if (process == exited) {
            process = null;
            if (!closing) {
                state = State.FAILED;
                logger.warn("Cobbleware managed Runtime exited with status {}", exited.exitValue());
            }
        }
    }

    private void waitUntilReady(Process running) throws IOException {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!running.isAlive()) {
                throw new IOException("Cobbleware Runtime exited before it became ready");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", configured.runtimePort()), 500);
                return;
            } catch (IOException ignored) {
                // The child process has not opened its loopback listener yet.
            }
            try {
                Thread.sleep(READY_RETRY_DELAY);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Cobbleware Runtime readiness", exception);
            }
        }
        throw new IOException(
                "Cobbleware Runtime did not become ready within " + READY_TIMEOUT.toSeconds() + " seconds");
    }

    private synchronized boolean isClosing() {
        return closing;
    }

    private void failStartup(Exception exception) {
        Process running;
        synchronized (this) {
            if (closing || state == State.FAILED) {
                return;
            }
            state = State.FAILED;
            running = process;
            process = null;
        }
        if (running != null) {
            running.destroy();
        }
        String prefix = configured.runtimeMode() == RuntimeMode.AUTO ? "Could not start optional" : "Could not start";
        logger.error("{} Cobbleware managed Runtime: {}", prefix, exception.getMessage());
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
    public void close() {
        Process running;
        Future<?> task;
        synchronized (this) {
            closing = true;
            state = State.STOPPED;
            task = startupTask;
            running = process;
            process = null;
        }
        if (task != null) {
            task.cancel(true);
        }
        startupExecutor.shutdownNow();
        if (running == null) {
            return;
        }
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

    private enum State {
        EXTERNAL,
        IDLE,
        STAGING,
        STARTING,
        RUNNING,
        FAILED,
        STOPPED
    }
}
