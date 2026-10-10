package io.github.mmcgill.cobbleware.config;

/** Immutable server configuration for Mod behavior and Runtime lifecycle settings. */
public record CobblewareConfig(
        int instrumentationWindowTicks,
        int maxRunningExecutionsPerPlayer,
        int maxQueuedEventsPerPlayer,
        int maxPendingEffectsPerPlayer,
        long maxTotalExecutionStateBytesPerPlayer,
        int guaranteedPoolSize,
        int bestEffortPoolSize,
        int runtimePort,
        String configurationError) {
    public CobblewareConfig {
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
        if (runtimePort <= 0 || runtimePort > 65_535) {
            throw new IllegalArgumentException("runtimePort must be between 1 and 65535");
        }
    }

    public static CobblewareConfig disabled(String reason) {
        return new CobblewareConfig(
                CobblewareConfigLoader.DEFAULT_INSTRUMENTATION_WINDOW_TICKS,
                CobblewareConfigLoader.DEFAULT_MAX_RUNNING_EXECUTIONS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_QUEUED_EVENTS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_PENDING_EFFECTS_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER,
                CobblewareConfigLoader.DEFAULT_GUARANTEED_POOL_SIZE,
                CobblewareConfigLoader.DEFAULT_BEST_EFFORT_POOL_SIZE,
                CobblewareConfigLoader.DEFAULT_RUNTIME_PORT,
                reason);
    }
}
