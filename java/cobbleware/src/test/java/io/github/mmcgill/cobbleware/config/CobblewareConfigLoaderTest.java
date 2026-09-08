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
        assertNull(config.configurationError());
        assertFalse(config.isEnabled());
        assertEquals("mainWorkerApiKey is empty", config.disabledReason());
        assertTrue(Files.exists(configPath));
        assertEquals(
                "{\n  \"mainWorkerUrl\": \"http://localhost:9000\",\n  \"mainWorkerApiKey\": \"\"\n}\n",
                Files.readString(configPath));
    }

    @Test
    void preservesBasePathsAndRejectsUnusableUrls() throws Exception {
        Path configPath = tempDir.resolve(CobblewareConfigLoader.FILE_NAME);
        Files.writeString(configPath, """
                {
                  "mainWorkerUrl": "https://example.test/cobbleware",
                  "mainWorkerApiKey": "secret"
                }
                """);

        CobblewareConfig configured = CobblewareConfigLoader.load(configPath, LOGGER);

        assertEquals("https://example.test/cobbleware/", configured.mainWorkerBaseUri().toString());
        assertTrue(configured.isEnabled());

        Files.writeString(configPath, """
                {
                  "mainWorkerUrl": "ftp://example.test",
                  "mainWorkerApiKey": "secret"
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
}
