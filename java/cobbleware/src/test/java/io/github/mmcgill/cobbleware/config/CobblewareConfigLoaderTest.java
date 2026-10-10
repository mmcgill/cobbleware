package io.github.mmcgill.cobbleware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("http://localhost:9000/", config.mainWorkerBaseUri().toString());
        assertEquals("", config.mainWorkerApiKey());
        assertEquals(600, config.instrumentationWindowTicks());
        assertEquals(10, config.maxRunningExecutionsPerPlayer());
        assertEquals(200, config.maxQueuedEventsPerPlayer());
        assertEquals(200, config.maxPendingEffectsPerPlayer());
        assertEquals(1_048_576, config.maxTotalExecutionStateBytesPerPlayer());
        assertEquals(0, config.guaranteedPoolSize());
        assertEquals(0, config.bestEffortPoolSize());
        assertEquals(RuntimeMode.AUTO, config.runtimeMode());
        assertEquals(9000, config.runtimePort());
        assertNull(config.configurationError());
        assertFalse(config.isEnabled());
        assertEquals("mainWorkerApiKey is empty", config.disabledReason());
        assertTrue(Files.exists(configPath));
        assertEquals(
                "{\n  \"mainWorkerUrl\": \"http://localhost:9000\",\n  \"mainWorkerApiKey\": \"\",\n"
                        + "  \"instrumentationWindowTicks\": 600,\n"
                        + "  \"maxRunningExecutionsPerPlayer\": 10,\n"
                        + "  \"maxQueuedEventsPerPlayer\": 200,\n"
                        + "  \"maxPendingEffectsPerPlayer\": 200,\n"
                        + "  \"maxTotalExecutionStateBytesPerPlayer\": 1048576,\n"
                        + "  \"bestEffortPoolSize\": 0\n}\n",
                Files.readString(configPath));
    }

    @Test
    void preservesBasePathsAndRejectsUnusableUrls() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "https://example.test/cobbleware",
              "mainWorkerApiKey": "secret",
              "instrumentationWindowTicks": 42,
              "maxRunningExecutionsPerPlayer": 7,
              "maxQueuedEventsPerPlayer": 123,
              "maxPendingEffectsPerPlayer": 45,
              "guaranteedPoolSize": 5,
              "bestEffortPoolSize": 2
            }
            """);

        CobblewareConfig configured = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals(
                "https://example.test/cobbleware/",
                configured.mainWorkerBaseUri().toString());
        assertEquals(42, configured.instrumentationWindowTicks());
        assertEquals(7, configured.maxRunningExecutionsPerPlayer());
        assertEquals(123, configured.maxQueuedEventsPerPlayer());
        assertEquals(45, configured.maxPendingEffectsPerPlayer());
        assertEquals(1_048_576, configured.maxTotalExecutionStateBytesPerPlayer());
        assertEquals(5, configured.guaranteedPoolSize());
        assertEquals(2, configured.bestEffortPoolSize());
        assertEquals(RuntimeMode.AUTO, configured.runtimeMode());
        assertEquals(9000, configured.runtimePort());
        assertTrue(configured.isEnabled());

        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "ftp://example.test",
              "mainWorkerApiKey": "secret",
              "instrumentationWindowTicks": 600,
              "maxRunningExecutionsPerPlayer": 10
            }
            """);
        CobblewareConfig invalid = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(invalid.isEnabled());
        assertEquals("Cobbleware configuration could not be loaded", invalid.disabledReason());
    }

    @Test
    void rejectsMissingOrWrongTypedProperties() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, "{\"mainWorkerUrl\": 42}");

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
        assertEquals("Cobbleware configuration could not be loaded", config.disabledReason());
    }

    @Test
    void rejectsInvalidInstrumentationWindow() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "instrumentationWindowTicks": 1.5
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
        assertEquals("Cobbleware configuration could not be loaded", config.disabledReason());
    }

    @Test
    void defaultsInstrumentationWindowForExistingConfiguration() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret"
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals(600, config.instrumentationWindowTicks());
        assertEquals(10, config.maxRunningExecutionsPerPlayer());
        assertEquals(200, config.maxQueuedEventsPerPlayer());
        assertEquals(200, config.maxPendingEffectsPerPlayer());
        assertEquals(1_048_576, config.maxTotalExecutionStateBytesPerPlayer());
        assertEquals(0, config.guaranteedPoolSize());
        assertEquals(0, config.bestEffortPoolSize());
        assertEquals(RuntimeMode.AUTO, config.runtimeMode());
        assertEquals(9000, config.runtimePort());
        assertTrue(config.isEnabled());
    }

    @Test
    void parsesManagedRuntimeModeAndPort() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "runtimeMode": "managed",
              "runtimePort": 9123
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals(RuntimeMode.MANAGED, config.runtimeMode());
        assertEquals(9123, config.runtimePort());
    }

    @Test
    void rejectsInvalidRuntimeModeAndPort() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "runtimeMode": "extract-only"
            }
            """);

        assertFalse(CobblewareConfigLoader.load(configPath, LOGGER).isEnabled());

        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "runtimePort": 65536
            }
            """);

        assertFalse(CobblewareConfigLoader.load(configPath, LOGGER).isEnabled());
    }

    @Test
    void rejectsInvalidRunningExecutionLimit() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "maxRunningExecutionsPerPlayer": 0
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
        assertEquals("Cobbleware configuration could not be loaded", config.disabledReason());
    }

    @Test
    void rejectsInvalidTotalExecutionStateLimit() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "maxTotalExecutionStateBytesPerPlayer": 1.5
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
    }

    @Test
    void rejectsInvalidQueuedEventLimit() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "maxQueuedEventsPerPlayer": 1.5
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
    }

    @Test
    void rejectsInvalidPendingEffectLimit() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "maxPendingEffectsPerPlayer": 0
            }
            """);

        CobblewareConfig config = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(config.isEnabled());
    }

    @Test
    void rejectsInvalidExecutionPoolSizes() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "guaranteedPoolSize": 0
            }
            """);

        CobblewareConfig invalidGuaranteed = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(invalidGuaranteed.isEnabled());

        Files.writeString(configPath, """
            {
              "mainWorkerUrl": "http://localhost:9000",
              "mainWorkerApiKey": "secret",
              "bestEffortPoolSize": -1
            }
            """);

        CobblewareConfig invalidBestEffort = CobblewareConfigLoader.load(configPath, LOGGER);

        assertFalse(invalidBestEffort.isEnabled());
    }
}
