package io.github.mmcgill.cobbleware.config;

import java.net.URI;

/** Immutable server configuration used to reach the Main Worker. */
public record CobblewareConfig(URI mainWorkerBaseUri, String mainWorkerApiKey, String configurationError) {
    public CobblewareConfig {
        if (mainWorkerApiKey == null) {
            throw new IllegalArgumentException("mainWorkerApiKey must not be null");
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
        return new CobblewareConfig(null, "", reason);
    }
}
