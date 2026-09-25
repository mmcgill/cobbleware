package io.github.mmcgill.cobbleware.config;

import java.net.URI;

/** Immutable server configuration used to reach the Main Worker and retain Mod instrumentation. */
public record CobblewareConfig(
        URI mainWorkerBaseUri,
        String mainWorkerApiKey,
        int instrumentationWindowTicks,
        int maxRunningExecutionsPerPlayer,
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
                reason);
    }
}
