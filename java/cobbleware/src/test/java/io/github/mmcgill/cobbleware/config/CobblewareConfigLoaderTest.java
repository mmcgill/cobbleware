package io.github.mmcgill.cobbleware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CobblewareConfigLoaderTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CobblewareConfigLoaderTest.class);

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultConfigurationWhenMissing() throws Exception {
        Path configPath = tempDir.resolve("config").resolve(CobblewareConfigLoader.FILE_NAME);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertDefaults(config);
        assertEquals(
                "{\n"
                        + "  \"runtimePort\": 9000,\n"
                        + "  \"instrumentationWindowTicks\": 600,\n"
                        + "  \"maxRunningExecutionsPerPlayer\": 10,\n"
                        + "  \"maxQueuedEventsPerPlayer\": 200,\n"
                        + "  \"maxPendingEffectsPerPlayer\": 200,\n"
                        + "  \"maxTotalExecutionStateBytesPerPlayer\": 1048576,\n"
                        + "  \"bestEffortPoolSize\": 0\n}\n",
                Files.readString(configPath));
    }

    @Test
    void acceptsLegacyConnectionSettingsAsIgnoredProperties() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
                {
                  "mainWorkerUrl": "https://example.test/cobbleware",
                  "mainWorkerApiKey": "secret",
                  "runtimeMode": "external",
                  "runtimePort": 9123,
                  "instrumentationWindowTicks": 42,
                  "maxRunningExecutionsPerPlayer": 7,
                  "maxQueuedEventsPerPlayer": 123,
                  "maxPendingEffectsPerPlayer": 45,
                  "maxTotalExecutionStateBytesPerPlayer": 1024,
                  "guaranteedPoolSize": 5,
                  "bestEffortPoolSize": 2
                }
                """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals(9123, config.runtimePort());
        assertEquals(42, config.instrumentationWindowTicks());
        assertEquals(7, config.maxRunningExecutionsPerPlayer());
        assertEquals(123, config.maxQueuedEventsPerPlayer());
        assertEquals(45, config.maxPendingEffectsPerPlayer());
        assertEquals(1024, config.maxTotalExecutionStateBytesPerPlayer());
        assertEquals(5, config.guaranteedPoolSize());
        assertEquals(2, config.bestEffortPoolSize());
        assertNull(config.configurationError());
    }

    @Test
    void rejectsInvalidSupportedSettings() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, "{\"runtimePort\": 65536}");

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals("Cobbleware configuration could not be loaded", config.configurationError());
    }

    private static void assertDefaults(CobblewareConfig config) {
        assertEquals(9000, config.runtimePort());
        assertEquals(600, config.instrumentationWindowTicks());
        assertEquals(10, config.maxRunningExecutionsPerPlayer());
        assertEquals(200, config.maxQueuedEventsPerPlayer());
        assertEquals(200, config.maxPendingEffectsPerPlayer());
        assertEquals(1_048_576, config.maxTotalExecutionStateBytesPerPlayer());
        assertEquals(0, config.guaranteedPoolSize());
        assertEquals(0, config.bestEffortPoolSize());
        assertNull(config.configurationError());
    }
}
