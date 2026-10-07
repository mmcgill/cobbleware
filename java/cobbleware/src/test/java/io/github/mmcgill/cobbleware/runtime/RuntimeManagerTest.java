package io.github.mmcgill.cobbleware.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import io.github.mmcgill.cobbleware.config.RuntimeMode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RuntimeManagerTest {
    @Test
    void managedRuntimeUsesNewLoopbackCredentialsForEveryPreparation() {
        CobblewareConfig config = config(RuntimeMode.MANAGED, "configured-key", 9123);

        RuntimeManager first = RuntimeManager.prepare(config, LoggerFactory.getLogger(getClass()));
        RuntimeManager second = RuntimeManager.prepare(config, LoggerFactory.getLogger(getClass()));

        assertEquals(
                "http://127.0.0.1:9123/",
                first.effectiveConfig().mainWorkerBaseUri().toString());
        assertNotEquals("configured-key", first.effectiveConfig().mainWorkerApiKey());
        assertNotEquals(
                first.effectiveConfig().mainWorkerApiKey(),
                second.effectiveConfig().mainWorkerApiKey());
    }

    @Test
    void externalRuntimePreservesConfiguredConnection() {
        CobblewareConfig config = config(RuntimeMode.EXTERNAL, "configured-key", 9123);

        RuntimeManager manager = RuntimeManager.prepare(config, LoggerFactory.getLogger(getClass()));

        assertEquals(config.mainWorkerBaseUri(), manager.effectiveConfig().mainWorkerBaseUri());
        assertEquals("configured-key", manager.effectiveConfig().mainWorkerApiKey());
    }

    @Test
    void commandBindsTheRuntimeToLoopbackWithItsIpOption() {
        List<String> command = RuntimeManager.runtimeCommand(
                new RuntimeDistribution.Artifacts(
                        Path.of("runtime.exe"), Path.of("main-worker.eszip"), Path.of("event-worker.eszip")),
                9123);

        assertEquals(List.of("--ip", "127.0.0.1", "--port", "9123"), command.subList(2, 6));
    }

    private static CobblewareConfig config(RuntimeMode mode, String apiKey, int port) {
        return new CobblewareConfig(
                URI.create("https://example.test/cobbleware/"),
                apiKey,
                600,
                10,
                200,
                200,
                1_048_576,
                0,
                0,
                mode,
                port,
                null);
    }
}
