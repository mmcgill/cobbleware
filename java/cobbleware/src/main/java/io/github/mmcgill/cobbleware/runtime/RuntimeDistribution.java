package io.github.mmcgill.cobbleware.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Reads and immutably stages one bundled Edge Runtime distribution. */
final class RuntimeDistribution {
    private static final String MANIFEST_RESOURCE = "cobbleware/runtime/manifest.json";
    private static final String RUNTIME_RESOURCE_PREFIX = "cobbleware/runtime/";
    private static final String WORKER_RESOURCE_PREFIX = "cobbleware/workers/";
    private final ClassLoader classLoader;

    RuntimeDistribution(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    boolean isBundled() {
        try (InputStream stream = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            return stream != null;
        } catch (IOException exception) {
            return false;
        }
    }

    Artifacts stage(Path worldRoot) throws IOException {
        byte[] manifestBytes = readResource(MANIFEST_RESOURCE);
        JsonObject manifest;
        try {
            JsonElement parsed =
                    JsonParser.parseString(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Runtime manifest must be a JSON object");
            }
            manifest = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Runtime manifest is not valid JSON", exception);
        }
        if (requiredInt(manifest, "schemaVersion") != 1) {
            throw new IOException("Unsupported Runtime manifest schema version");
        }
        String platform = platform();
        JsonObject platforms = requiredObject(manifest, "platforms");
        JsonElement platformElement = platforms.get(platform);
        if (platformElement == null || !platformElement.isJsonObject()) {
            throw new IOException("Bundled Runtime does not support platform " + platform);
        }
        List<FileSpec> runtimeFiles = files(requiredArray(platformElement.getAsJsonObject(), "files"));
        List<FileSpec> workerFiles = files(requiredArray(manifest, "workers"));
        FileSpec mainWorker = find(workerFiles, "main-worker.eszip");
        FileSpec eventWorker = find(workerFiles, "event-worker.eszip");
        if (runtimeFiles.size() != 1) {
            throw new IOException("Runtime manifest must contain exactly one native executable for " + platform);
        }
        FileSpec executable = runtimeFiles.getFirst();
        String distributionId = requiredString(manifest, "distributionId").toLowerCase(Locale.ROOT);
        if (!distributionId.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime manifest contains an invalid distribution identity");
        }
        Path root = worldRoot.resolve("cobbleware-data").resolve("runtime").resolve(platform);
        Path target = root.resolve(distributionId);
        Artifacts artifacts = artifacts(target, executable, mainWorker, eventWorker);
        if (Files.exists(target)) {
            verifyExisting(artifacts.binary(), executable);
            verifyExisting(artifacts.mainWorker(), mainWorker);
            verifyExisting(artifacts.eventWorker(), eventWorker);
            return artifacts;
        }

        Files.createDirectories(root);
        Path temporary = root.resolve(distributionId + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(temporary);
        try {
            writeVerified(
                    temporary.resolve("runtime").resolve(executable.path()),
                    RUNTIME_RESOURCE_PREFIX + platform + "/" + executable.path(),
                    executable);
            writeVerified(
                    temporary.resolve("workers").resolve(mainWorker.path()),
                    WORKER_RESOURCE_PREFIX + mainWorker.path(),
                    mainWorker);
            writeVerified(
                    temporary.resolve("workers").resolve(eventWorker.path()),
                    WORKER_RESOURCE_PREFIX + eventWorker.path(),
                    eventWorker);
            if (!isWindows()
                    && !temporary
                            .resolve("runtime")
                            .resolve(executable.path())
                            .toFile()
                            .setExecutable(true, true)) {
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
                deleteTemporaryDirectory(temporary);
            }
        }
        if (!Files.isRegularFile(artifacts.binary())
                || !Files.isRegularFile(artifacts.mainWorker())
                || !Files.isRegularFile(artifacts.eventWorker())) {
            throw new IOException("Runtime staging did not produce all expected files");
        }
        return artifacts;
    }

    private static Artifacts artifacts(Path root, FileSpec executable, FileSpec mainWorker, FileSpec eventWorker) {
        return new Artifacts(
                root.resolve("runtime").resolve(executable.path()),
                root.resolve("workers").resolve(mainWorker.path()),
                root.resolve("workers").resolve(eventWorker.path()));
    }

    private void writeVerified(Path destination, String resource, FileSpec file) throws IOException {
        byte[] bytes = readResource(resource);
        if (!sha256(bytes).equals(file.sha256())) {
            throw new IOException("Checksum mismatch for bundled Runtime resource " + resource);
        }
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
    }

    private static void verifyExisting(Path file, FileSpec specification) throws IOException {
        if (!Files.isRegularFile(file) || !sha256(Files.readAllBytes(file)).equals(specification.sha256())) {
            throw new IOException("Existing Runtime distribution is incomplete or corrupted: " + file);
        }
    }

    private byte[] readResource(String resource) throws IOException {
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Bundled Runtime resource is missing: " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static List<FileSpec> files(JsonArray values) throws IOException {
        List<FileSpec> result = new ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                throw new IOException("Runtime manifest file entry must be an object");
            }
            JsonObject file = element.getAsJsonObject();
            String path = requiredString(file, "path");
            Path parsed = Path.of(path);
            if (parsed.isAbsolute() || parsed.getNameCount() != 1 || path.contains("\\")) {
                throw new IOException("Runtime manifest contains an unsafe file path");
            }
            String sha256 = requiredString(file, "sha256").toLowerCase(Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Runtime manifest contains an invalid SHA-256 checksum");
            }
            result.add(new FileSpec(path, sha256));
        }
        return result;
    }

    private static FileSpec find(List<FileSpec> files, String expectedPath) throws IOException {
        return files.stream()
                .filter(file -> file.path().equals(expectedPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Runtime manifest is missing worker " + expectedPath));
    }

    private static JsonObject requiredObject(JsonObject object, String property) throws IOException {
        JsonElement value = object.get(property);
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Runtime manifest " + property + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String property) throws IOException {
        JsonElement value = object.get(property);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Runtime manifest " + property + " must be an array");
        }
        return value.getAsJsonArray();
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

    private static String platform() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && (architecture.equals("amd64") || architecture.equals("x86_64"))) {
            return "windows-x86_64";
        }
        throw new IOException("Unsupported Runtime platform " + os + "-" + architecture);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
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

    record Artifacts(Path binary, Path mainWorker, Path eventWorker) {}

    private record FileSpec(String path, String sha256) {}
}
