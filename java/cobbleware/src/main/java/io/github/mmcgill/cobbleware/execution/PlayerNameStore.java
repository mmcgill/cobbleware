package io.github.mmcgill.cobbleware.execution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Stores the last observed profile name for Cobbleware players, keyed by stable player UUID. */
public final class PlayerNameStore implements AutoCloseable {
    static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson JSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final Map<UUID, String> players = new HashMap<>();
    private Path file;
    private MinecraftServer server;
    private ExecutorService ioExecutor;
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);
    private long generation;
    private long queuedGeneration;
    private boolean available;
    private boolean closing;

    public PlayerNameStore(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("player name store logger must not be null");
        }
        this.logger = logger;
    }

    /** Initializes the cache for the current server world. */
    public synchronized void initialize(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server must not be null");
        }
        if (isInitialized() && !closing) {
            throw new IllegalStateException("Player name store is already initialized");
        }

        Path nextFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("cobbleware-data")
                .resolve("player-names.json");
        try {
            Files.createDirectories(nextFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create Cobbleware player-name directory " + nextFile.getParent(), exception);
        }

        this.file = nextFile;
        this.server = server;
        ioExecutor = newIoExecutor();
        players.clear();
        writeTail = CompletableFuture.completedFuture(null);
        generation = 0;
        queuedGeneration = 0;
        closing = false;
        available = true;
        try {
            if (Files.exists(file)) {
                players.putAll(decode(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException exception) {
            available = false;
            logger.error(
                    "Could not load Cobbleware player names from {}; owner names will be unavailable", file, exception);
        }
    }

    /** Returns the most recently observed name for a player, if the cache has one. */
    public synchronized Optional<String> nameFor(UUID playerId) {
        requirePlayerId(playerId);
        return Optional.ofNullable(players.get(playerId));
    }

    /** Records a player's current profile name, if it differs from the cached name. */
    public synchronized void record(UUID playerId, String playerName) {
        requireInitialized();
        requirePlayerId(playerId);
        requirePlayerName(playerName);
        if (!available || closing || playerName.equals(players.get(playerId))) {
            return;
        }
        players.put(playerId, playerName);
        generation += 1;
    }

    /** Queues an atomic write before Minecraft saves its worlds. */
    public synchronized void beforeSave() {
        requireInitialized();
        if (available && !closing) {
            queueWrite();
        }
    }

    /** Synchronously flushes the final cache snapshot during server shutdown. */
    @Override
    public void close() {
        Path target;
        Map<UUID, String> snapshot;
        ExecutorService executor;
        CompletableFuture<Void> outstandingWrite;
        boolean shouldWrite;
        synchronized (this) {
            requireInitialized();
            if (closing) {
                return;
            }
            closing = true;
            target = file;
            snapshot = Map.copyOf(players);
            executor = ioExecutor;
            outstandingWrite = writeTail;
            shouldWrite = available;
        }
        outstandingWrite.handle((ignored, failure) -> null).join();
        if (shouldWrite) {
            try {
                ExecutionStateStore.writeAtomically(target, encode(snapshot).getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                logger.error("Could not synchronously save Cobbleware player names to {}", target, exception);
            }
        }
        executor.shutdown();
    }

    static String encode(Map<UUID, String> playerNames) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", CURRENT_FORMAT_VERSION);
        JsonObject players = new JsonObject();
        playerNames.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> players.addProperty(entry.getKey().toString(), entry.getValue()));
        root.add("players", players);
        return JSON.toJson(root) + "\n";
    }

    static Map<UUID, String> decode(String contents) {
        JsonElement rootElement = JsonParser.parseString(contents);
        if (!rootElement.isJsonObject()) {
            throw new JsonParseException("player-name file root must be an object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        if (requiredInteger(root, "formatVersion") != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("unsupported player-name file format version");
        }
        JsonElement playersElement = root.get("players");
        if (playersElement == null || !playersElement.isJsonObject()) {
            throw new JsonParseException("player-name file players must be an object");
        }

        Map<UUID, String> decoded = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry :
                playersElement.getAsJsonObject().entrySet()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("player-name file contains an invalid player UUID", exception);
            }
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new JsonParseException("player name must be a string");
            }
            String playerName = entry.getValue().getAsString();
            try {
                requirePlayerName(playerName);
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("player name must not be empty", exception);
            }
            if (decoded.put(playerId, playerName) != null) {
                throw new JsonParseException("player-name file contains a duplicate player UUID");
            }
        }
        return Map.copyOf(decoded);
    }

    private static int requiredInteger(JsonObject object, String property) {
        JsonElement value = object.get(property);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(property + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new JsonParseException(property + " must be an integer", exception);
        }
    }

    private static ExecutorService newIoExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Cobbleware player-name writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    private synchronized void queueWrite() {
        if (generation <= queuedGeneration) {
            return;
        }
        long queued = generation;
        byte[] bytes = encode(Map.copyOf(players)).getBytes(StandardCharsets.UTF_8);
        queuedGeneration = queued;
        writeTail = writeTail
                .handle((ignored, failure) -> null)
                .thenRunAsync(
                        () -> {
                            try {
                                ExecutionStateStore.writeAtomically(file, bytes);
                            } catch (IOException exception) {
                                throw new PlayerNameWriteException(exception);
                            }
                        },
                        ioExecutor);
        writeTail.whenComplete((ignored, failure) -> completeWrite(queued, failure));
    }

    private void completeWrite(long completedGeneration, Throwable failure) {
        server.execute(() -> {
            synchronized (PlayerNameStore.this) {
                if (failure != null) {
                    logger.error("Could not save Cobbleware player names to {}", file, failure);
                    if (queuedGeneration == completedGeneration) {
                        queuedGeneration = 0;
                    }
                }
            }
        });
    }

    private void requireInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("Player name store has not been initialized");
        }
    }

    private boolean isInitialized() {
        return file != null && server != null && ioExecutor != null;
    }

    private static void requirePlayerId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("player UUID must not be null");
        }
    }

    private static void requirePlayerName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            throw new IllegalArgumentException("player name must not be null or empty");
        }
    }

    private static final class PlayerNameWriteException extends RuntimeException {
        private PlayerNameWriteException(IOException cause) {
            super(cause);
        }
    }
}
