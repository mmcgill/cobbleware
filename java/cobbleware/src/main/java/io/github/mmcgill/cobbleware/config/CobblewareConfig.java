package io.github.mmcgill.cobbleware.config;

import java.net.URI;

/** Immutable server configuration used to reach the Main Worker and retain Mod instrumentation. */
public record CobblewareConfig(
        URI mainWorkerBaseUri,
        String mainWorkerApiKey,
        int instrumentationWindowTicks,
        int maxRunningExecutionsPerPlayer,
        int maxQueuedEventsPerPlayer,
        int maxPendingEffectsPerPlayer,
        long maxTotalExecutionStateBytesPerPlayer,
        int guaranteedPoolSize,
        int bestEffortPoolSize,
        RuntimeMode runtimeMode,
        int runtimePort,
        String configurationError) {
    public CobblewareConfig {
        if (mainWorkerApiKey == null) {
            throw new IllegalArgumentException("mainWorkerApiKey must not be null");
        }
        if (instrumentationWindowTicks <= 0) {
            throw new IllegalArgumentException("instrumentationWindowTicks must be positive");
        }
        if (maxRunningExecutionsPerPlayer <= 0) {
            throw new IllegalArgumentException("maxRunningExecutionsPerPlayer must be positive");
        }
        if (maxQueuedEventsPerPlayer <= 0) {
            throw new IllegalArgumentException("maxQueuedEventsPerPlayer must be positive");
        }
        if (maxPendingEffectsPerPlayer <= 0) {
            throw new IllegalArgumentException("maxPendingEffectsPerPlayer must be positive");
        }
        if (maxTotalExecutionStateBytesPerPlayer <= 0) {
            throw new IllegalArgumentException("maxTotalExecutionStateBytesPerPlayer must be positive");
        }
        if (guaranteedPoolSize < 0) {
            throw new IllegalArgumentException("guaranteedPoolSize must not be negative");
        }
        if (bestEffortPoolSize < 0) {
            throw new IllegalArgumentException("bestEffortPoolSize must not be negative");
        }
        if (runtimeMode == null) {
            throw new IllegalArgumentException("runtimeMode must not be null");
        }
        if (runtimePort <= 0 || runtimePort > 65_535) {
            throw new IllegalArgumentException("runtimePort must be between 1 and 65535");
        }
    }

    /** Compatibility constructor for callers that do not need Runtime lifecycle settings. */
    public CobblewareConfig(
            URI mainWorkerBaseUri,
            String mainWorkerApiKey,
            int instrumentationWindowTicks,
            int maxRunningExecutionsPerPlayer,
            int maxQueuedEventsPerPlayer,
            int maxPendingEffectsPerPlayer,
            long maxTotalExecutionStateBytesPerPlayer,
            int guaranteedPoolSize,
            int bestEffortPoolSize,
            String configurationError) {
        this(
                mainWorkerBaseUri,
                mainWorkerApiKey,
                instrumentationWindowTicks,
                maxRunningExecutionsPerPlayer,
                maxQueuedEventsPerPlayer,
                maxPendingEffectsPerPlayer,
                maxTotalExecutionStateBytesPerPlayer,
                guaranteedPoolSize,
                bestEffortPoolSize,
                RuntimeMode.AUTO,
                CobblewareConfigLoader.DEFAULT_RUNTIME_PORT,
                configurationError);
    }

    /** Returns whether the configuration has a usable URL and non-empty API key. */
    public boolean isEnabled() {
        return configurationError == null && !mainWorkerApiKey.isEmpty();
    }

    /** Returns a concise reason why requests cannot be made, or {@code null} when enabled. */
    public String disabledReason() {
        if (configurationError != null) {
            return configurationError;
        }
        if (mainWorkerApiKey.isEmpty()) {
            return "mainWorkerApiKey is empty";
        }
        return null;
    }

    public static CobblewareConfig disabled(String reason) {
        return new CobblewareConfig(
                null,
                "",
                CobblewareConfigLoader.DEFAULT_INSTRUMENTATION_WINDOW_TICKS,
                CobblewareConfigLoader.DEFAULT_MAX_RUNNING_EXECUTIONS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_QUEUED_EVENTS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_PENDING_EFFECTS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_GUARANTEED_POOL_SIZE,
                CobblewareConfigLoader.DEFAULT_BEST_EFFORT_POOL_SIZE,
                RuntimeMode.AUTO,
                CobblewareConfigLoader.DEFAULT_RUNTIME_PORT,
                reason);
    }

    /** Returns a copy with the Main Worker connection selected for this server lifetime. */
    public CobblewareConfig withMainWorkerConnection(URI baseUri, String apiKey) {
        return new CobblewareConfig(
                baseUri,
                apiKey,
                instrumentationWindowTicks,
                maxRunningExecutionsPerPlayer,
                maxQueuedEventsPerPlayer,
                maxPendingEffectsPerPlayer,
                maxTotalExecutionStateBytesPerPlayer,
                guaranteedPoolSize,
                bestEffortPoolSize,
                runtimeMode,
                runtimePort,
                configurationError);
    }
}
