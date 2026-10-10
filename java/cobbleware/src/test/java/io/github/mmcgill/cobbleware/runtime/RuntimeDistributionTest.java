package io.github.mmcgill.cobbleware.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeDistributionTest {
    private static final byte[] BINARY = "runtime-binary".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAIN_WORKER = "main-worker".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_WORKER = "event-worker".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void downloadsStagesAndReusesThePinnedLinuxRuntimeWhileRefreshingWorkers() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        RuntimeDistribution distribution = distribution(
                manifest(BINARY),
                (source, destination, expectedSha256) -> {
                    assertEquals(
                            "https://github.com/mmcgill/cobbleware/releases/download/test-release/edge-runtime-linux-x86_64",
                            source.toString());
                    downloads.incrementAndGet();
                    Files.write(destination, BINARY);
                },
                "Linux",
                "amd64");

        RuntimeDistribution.Artifacts first = distribution.stage(temporaryDirectory);
        Files.writeString(first.mainWorker(), "stale");
        RuntimeDistribution.Artifacts second = distribution.stage(temporaryDirectory);

        assertEquals(first, second);
        assertEquals(1, downloads.get());
        assertEquals(
                temporaryDirectory
                        .resolve("cobbleware-data/runtime/linux-x86_64")
                        .resolve(sha256(BINARY)),
                first.binary());
        assertArrayEquals(BINARY, Files.readAllBytes(first.binary()));
        assertArrayEquals(MAIN_WORKER, Files.readAllBytes(first.mainWorker()));
        assertArrayEquals(EVENT_WORKER, Files.readAllBytes(first.eventWorker()));
    }

    @Test
    void replacesACorruptCachedRuntimeInsteadOfUsingIt() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        RuntimeDistribution distribution = distribution(
                manifest(BINARY),
                (source, destination, expectedSha256) -> {
                    downloads.incrementAndGet();
                    Files.write(destination, BINARY);
                },
                "Windows 11",
                "x86_64");

        RuntimeDistribution.Artifacts first = distribution.stage(temporaryDirectory);
        Files.writeString(first.binary(), "corrupt");
        RuntimeDistribution.Artifacts replacement = distribution.stage(temporaryDirectory);

        assertEquals(2, downloads.get());
        assertArrayEquals(BINARY, Files.readAllBytes(replacement.binary()));
    }

    @Test
    void rejectsADownloadWhoseDigestDoesNotMatchThePin() {
        RuntimeDistribution distribution = distribution(
                manifest(BINARY),
                (source, destination, expectedSha256) -> Files.writeString(destination, "tampered"),
                "Linux",
                "x86_64");

        assertThrows(java.io.IOException.class, () -> distribution.stage(temporaryDirectory));
        assertFalse(Files.exists(temporaryDirectory
                .resolve("cobbleware-data/runtime/linux-x86_64")
                .resolve(sha256(BINARY))));
    }

    @Test
    void rejectsUnsupportedPlatformsBeforeDownloading() {
        AtomicInteger downloads = new AtomicInteger();
        RuntimeDistribution distribution = distribution(
                manifest(BINARY),
                (source, destination, expectedSha256) -> downloads.incrementAndGet(),
                "Mac OS X",
                "aarch64");

        assertThrows(java.io.IOException.class, () -> distribution.stage(temporaryDirectory));
        assertEquals(0, downloads.get());
    }

    @Test
    void rejectsAnUnsafeNativeAssetBeforeDownloading() {
        AtomicInteger downloads = new AtomicInteger();
        RuntimeDistribution distribution = distribution(
                manifest(BINARY).replace("\"asset\": \"edge-runtime-linux-x86_64\"", "\"asset\": \"../runtime\""),
                (source, destination, expectedSha256) -> downloads.incrementAndGet(),
                "Linux",
                "x86_64");

        assertThrows(java.io.IOException.class, () -> distribution.stage(temporaryDirectory));
        assertEquals(0, downloads.get());
    }

    @Test
    void stagesAConfiguredLocalRuntimeWithoutDownloading() throws Exception {
        Path localRuntime = temporaryDirectory.resolve("local-runtime.exe");
        Files.write(localRuntime, BINARY);
        String property = "cobbleware.localRuntime";
        String previous = System.getProperty(property);
        System.setProperty(property, localRuntime.toString());
        try {
            RuntimeDistribution distribution = distribution(
                    manifest(BINARY),
                    (source, destination, expectedSha256) -> {
                        throw new AssertionError("Local Runtime should not be downloaded");
                    },
                    "Windows 11",
                    "amd64");

            RuntimeDistribution.Artifacts artifacts = distribution.stage(temporaryDirectory.resolve("world"));

            assertArrayEquals(BINARY, Files.readAllBytes(artifacts.binary()));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static RuntimeDistribution distribution(
            String manifest,
            RuntimeDistribution.RuntimeDownloader downloader,
            String operatingSystem,
            String architecture) {
        return new RuntimeDistribution(resources(manifest), downloader, operatingSystem, architecture);
    }

    private static ClassLoader resources(String manifest) {
        Map<String, byte[]> resources = Map.of(
                "cobbleware/runtime/manifest.json", manifest.getBytes(StandardCharsets.UTF_8),
                "cobbleware/workers/main-worker.eszip", MAIN_WORKER,
                "cobbleware/workers/event-worker.eszip", EVENT_WORKER);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] bytes = resources.get(name);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
    }

    private static String manifest(byte[] binary) {
        return """
                {
                  "schemaVersion": 3,
                  "release": {
                    "repository": "mmcgill/cobbleware",
                    "tag": "test-release"
                  },
                  "platforms": {
                    "windows-x86_64": {
                      "asset": "edge-runtime-windows-x86_64.exe",
                      "sha256": "%s"
                    },
                    "linux-x86_64": {
                      "asset": "edge-runtime-linux-x86_64",
                      "sha256": "%s"
                    }
                  }
                }
                """.formatted(sha256(binary), sha256(binary));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
