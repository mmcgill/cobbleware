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
        assertEquals(1_048_576, config.maxTotalExecutionStateBytesPerPlayer());
        assertNull(config.configurationError());
        assertFalse(config.isEnabled());
        assertEquals("mainWorkerApiKey is empty", config.disabledReason());
        assertTrue(Files.exists(configPath));
        assertEquals(
                "{\n  \"mainWorkerUrl\": \"http://localhost:9000\",\n  \"mainWorkerApiKey\": \"\",\n"
                        + "  \"instrumentationWindowTicks\": 600,\n"
                        + "  \"maxRunningExecutionsPerPlayer\": 10,\n"
                        + "  \"maxQueuedEventsPerPlayer\": 200,\n"
                        + "  \"maxTotalExecutionStateBytesPerPlayer\": 1048576\n}\n",
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
              "maxQueuedEventsPerPlayer": 123
            }
            """);

        CobblewareConfig configured = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals(
                "https://example.test/cobbleware/",
                configured.mainWorkerBaseUri().toString());
        assertEquals(42, configured.instrumentationWindowTicks());
        assertEquals(7, configured.maxRunningExecutionsPerPlayer());
        assertEquals(123, configured.maxQueuedEventsPerPlayer());
        assertEquals(1_048_576, configured.maxTotalExecutionStateBytesPerPlayer());
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
        assertEquals(1_048_576, config.maxTotalExecutionStateBytesPerPlayer());
        assertTrue(config.isEnabled());
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
}
