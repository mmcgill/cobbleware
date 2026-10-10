package io.github.mmcgill.cobbleware.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;

/** Downloads and immutably stages one pinned Edge Runtime distribution. */
final class RuntimeDistribution {
    private static final String MANIFEST_RESOURCE = "cobbleware/runtime/manifest.json";
    private static final String WORKER_RESOURCE_PREFIX = "cobbleware/workers/";
    private static final String RELEASE_REPOSITORY = "mmcgill/cobbleware";
    private static final String LOCAL_RUNTIME_PROPERTY = "cobbleware.localRuntime";
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("windows-x86_64", "linux-x86_64");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private final ClassLoader classLoader;
    private final RuntimeDownloader downloader;
    private final String operatingSystem;
    private final String architecture;
    private final Logger logger;

    RuntimeDistribution(ClassLoader classLoader) {
        this(
                classLoader,
                new HttpRuntimeDownloader(),
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                null);
    }

    RuntimeDistribution(ClassLoader classLoader, Logger logger) {
        this(
                classLoader,
                new HttpRuntimeDownloader(),
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                logger);
    }

    RuntimeDistribution(
            ClassLoader classLoader, RuntimeDownloader downloader, String operatingSystem, String architecture) {
        this(classLoader, downloader, operatingSystem, architecture, null);
    }

    RuntimeDistribution(
            ClassLoader classLoader,
            RuntimeDownloader downloader,
            String operatingSystem,
            String architecture,
            Logger logger) {
        this.classLoader = classLoader;
        this.downloader = downloader;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.logger = logger;
    }

    Artifacts stage(Path worldRoot) throws IOException {
        DistributionSpec distribution = readDistribution();
        Path root = worldRoot.resolve("cobbleware-data").resolve("runtime").resolve(distribution.platform());
        Path target = root.resolve(distribution.executable().sha256());
        Artifacts artifacts =
                artifacts(target, worldRoot.resolve("cobbleware-data").resolve("workers"));
        extractWorkers(artifacts);
        if (Files.exists(target)) {
            try {
                verifyExisting(artifacts.binary(), distribution.executable());
                logInfo(
                        "Reusing verified Cobbleware Runtime {} ({}) from {}",
                        distribution.releaseTag(),
                        distribution.platform(),
                        target);
                return artifacts;
            } catch (IOException exception) {
                deleteDirectory(target);
            }
        }

        Files.createDirectories(root);
        Path temporary = root.resolve(distribution.executable().sha256() + ".tmp-" + UUID.randomUUID());
        try {
            Path binary = temporary;
            Files.createDirectories(binary.getParent());
            boolean downloaded = distribution.localBinary() == null;
            if (distribution.localBinary() == null) {
                URI source = downloadUri(distribution);
                logInfo(
                        "Starting Cobbleware Runtime download: release {} ({}) from {}",
                        distribution.releaseTag(),
                        distribution.platform(),
                        source);
                downloader.download(source, binary, distribution.executable().sha256());
            } else {
                logInfo(
                        "Staging configured local Cobbleware Runtime for {} from {}",
                        distribution.platform(),
                        distribution.localBinary());
                Files.copy(distribution.localBinary(), binary);
            }
            verifyExisting(binary, distribution.executable());
            if (downloaded) {
                logInfo(
                        "Finished Cobbleware Runtime download: release {} ({}) passed SHA-256 verification",
                        distribution.releaseTag(),
                        distribution.platform());
            }
            if (!distribution.platform().startsWith("windows-")
                    && !binary.toFile().setExecutable(true, true)) {
                throw new IOException("Could not mark Runtime executable as runnable");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ignored) {
                // Another server process staged this immutable distribution first.
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Runtime distribution filesystem does not support atomic staging", exception);
            }
        } finally {
            if (Files.exists(temporary)) {
                deleteDirectory(temporary);
            }
        }
        verifyExisting(artifacts.binary(), distribution.executable());
        logInfo(
                "Finished staging Cobbleware Runtime {} ({}) in {}",
                distribution.releaseTag(),
                distribution.platform(),
                target);
        return artifacts;
    }

    private void logInfo(String message, Object... arguments) {
        if (logger != null) {
            logger.info(message, arguments);
        }
    }

    private DistributionSpec readDistribution() throws IOException {
        JsonObject manifest = readManifest();
        if (requiredInt(manifest, "schemaVersion") != 3) {
            throw new IOException("Unsupported Runtime manifest schema version");
        }
        JsonObject release = requiredObject(manifest, "release");
        String repository = requiredString(release, "repository");
        if (!RELEASE_REPOSITORY.equals(repository)) {
            throw new IOException("Runtime manifest contains an unsupported release repository");
        }
        String releaseTag = requiredString(release, "tag");
        requireSafeSegment(releaseTag, "release tag");
        String platform = platform(operatingSystem, architecture);
        JsonObject platforms = requiredObject(manifest, "platforms");
        for (Map.Entry<String, JsonElement> entry : platforms.entrySet()) {
            if (!SUPPORTED_PLATFORMS.contains(entry.getKey())
                    || !entry.getValue().isJsonObject()) {
                throw new IOException("Runtime manifest contains an unsupported platform entry");
            }
        }
        JsonElement platformElement = platforms.get(platform);
        if (platformElement == null || !platformElement.isJsonObject()) {
            throw new IOException("Pinned Runtime does not support platform " + platform);
        }
        Path localBinary = localRuntime();
        FileSpec executable =
                localBinary == null ? nativeFile(platformElement.getAsJsonObject()) : localFile(localBinary);
        return new DistributionSpec(repository, releaseTag, platform, executable, localBinary);
    }

    private JsonObject readManifest() throws IOException {
        byte[] manifestBytes = readResource(MANIFEST_RESOURCE);
        try {
            JsonElement parsed = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Runtime manifest must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Runtime manifest is not valid JSON", exception);
        }
    }

    private static Artifacts artifacts(Path binary, Path workers) {
        return new Artifacts(binary, workers.resolve("main-worker.eszip"), workers.resolve("event-worker.eszip"));
    }

    private static URI downloadUri(DistributionSpec distribution) {
        return URI.create("https://github.com/" + distribution.repository() + "/releases/download/"
                + distribution.releaseTag() + "/" + distribution.executable().path());
    }

    private void extractWorkers(Artifacts artifacts) throws IOException {
        writeResource(artifacts.mainWorker(), WORKER_RESOURCE_PREFIX + "main-worker.eszip");
        writeResource(artifacts.eventWorker(), WORKER_RESOURCE_PREFIX + "event-worker.eszip");
    }

    private void writeResource(Path destination, String resource) throws IOException {
        byte[] bytes = readResource(resource);
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
    }

    private static void verifyExisting(Path file, FileSpec specification) throws IOException {
        if (!Files.isRegularFile(file) || !sha256(file).equals(specification.sha256())) {
            throw new IOException("Runtime distribution is incomplete or corrupted: " + file);
        }
    }

    private byte[] readResource(String resource) throws IOException {
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Runtime resource is missing: " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static FileSpec nativeFile(JsonObject object) throws IOException {
        String asset = requiredString(object, "asset");
        requireSafeSegment(asset, "native asset");
        return new FileSpec(asset, checksum(object));
    }

    private static Path localRuntime() throws IOException {
        String configured = System.getProperty(LOCAL_RUNTIME_PROPERTY, "");
        if (configured.isBlank()) {
            return null;
        }
        try {
            Path local = Path.of(configured).toAbsolutePath();
            if (!Files.isRegularFile(local)) {
                throw new IOException("Configured local Runtime is not a regular file: " + local);
            }
            return local;
        } catch (java.nio.file.InvalidPathException exception) {
            throw new IOException("Configured local Runtime path is invalid", exception);
        }
    }

    private static FileSpec localFile(Path local) throws IOException {
        Path name = local.getFileName();
        if (name == null) {
            throw new IOException("Configured local Runtime has no file name");
        }
        String asset = name.toString();
        requireSafeSegment(asset, "local Runtime file name");
        return new FileSpec(asset, sha256(local));
    }

    private static String checksum(JsonObject object) throws IOException {
        String sha256 = requiredString(object, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime manifest contains an invalid SHA-256 checksum");
        }
        return sha256;
    }

    private static JsonObject requiredObject(JsonObject object, String property) throws IOException {
        JsonElement value = object.get(property);
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Runtime manifest " + property + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static int requiredInt(JsonObject object, String property) throws IOException {
        JsonElement value = object.get(property);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Runtime manifest " + property + " must be a number");
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException exception) {
            throw new IOException("Runtime manifest " + property + " must be an integer", exception);
        }
    }

    private static String requiredString(JsonObject object, String property) throws IOException {
        JsonElement value = object.get(property);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Runtime manifest " + property + " must be a string");
        }
        return value.getAsString();
    }

    private static void requireSafeSegment(String value, String description) throws IOException {
        if (!value.matches("[A-Za-z0-9._-]+") || value.equals(".") || value.equals("..")) {
            throw new IOException("Runtime manifest contains an unsafe " + description);
        }
    }

    private static String platform(String operatingSystem, String architecture) throws IOException {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        if ((arch.equals("amd64") || arch.equals("x86_64")) && os.contains("win")) {
            return "windows-x86_64";
        }
        if ((arch.equals("amd64") || arch.equals("x86_64")) && os.contains("linux")) {
            return "linux-x86_64";
        }
        throw new IOException("Unsupported Runtime platform " + os + "-" + arch);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return digest(stream);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String digest(InputStream stream) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[16 * 1024];
        for (int read; (read = stream.read(buffer)) >= 0; ) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    @FunctionalInterface
    interface RuntimeDownloader {
        void download(URI source, Path destination, String expectedSha256) throws IOException;
    }

    private static final class HttpRuntimeDownloader implements RuntimeDownloader {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public void download(URI source, Path destination, String expectedSha256) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    try (InputStream ignored = response.body()) {
                        throw new IOException("Runtime download returned HTTP " + response.statusCode());
                    }
                }
                try (InputStream input = response.body();
                        OutputStream output = Files.newOutputStream(destination)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[16 * 1024];
                    for (int read; (read = input.read(buffer)) >= 0; ) {
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                    }
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!actual.equals(expectedSha256)) {
                        throw new IOException("Downloaded Runtime checksum does not match the pinned release");
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading Runtime", exception);
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException("SHA-256 is unavailable", exception);
            }
        }
    }

    record Artifacts(Path binary, Path mainWorker, Path eventWorker) {}

    private record FileSpec(String path, String sha256) {}

    private record DistributionSpec(
            String repository, String releaseTag, String platform, FileSpec executable, Path localBinary) {}
}
