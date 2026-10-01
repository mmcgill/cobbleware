package io.github.mmcgill.cobbleware.config;

import java.net.URI;

/** Immutable server configuration used to reach the Main Worker and retain Mod instrumentation. */
public record CobblewareConfig(
        URI mainWorkerBaseUri,
        String mainWorkerApiKey,
        int instrumentationWindowTicks,
        int maxRunningExecutionsPerPlayer,
        int maxQueuedEventsPerPlayer,
        long maxTotalExecutionStateBytesPerPlayer,
        int guaranteedPoolSize,
        int bestEffortPoolSize,
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
        if (maxTotalExecutionStateBytesPerPlayer <= 0) {
            throw new IllegalArgumentException("maxTotalExecutionStateBytesPerPlayer must be positive");
        }
        if (guaranteedPoolSize < 0) {
            throw new IllegalArgumentException("guaranteedPoolSize must not be negative");
        }
        if (bestEffortPoolSize < 0) {
            throw new IllegalArgumentException("bestEffortPoolSize must not be negative");
        }
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
                CobblewareConfigLoader.DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_GUARANTEED_POOL_SIZE,
                CobblewareConfigLoader.DEFAULT_BEST_EFFORT_POOL_SIZE,
                reason);
    }
}
