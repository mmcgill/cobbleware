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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Stores durable, administrator-configured online and offline execution priorities for players. */
public final class PlayerPriorityStore implements AutoCloseable {
    static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 3;
    public static final Priorities DEFAULT_PRIORITIES = new Priorities(2, 3);

    private static final Gson JSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final Map<UUID, Priorities> players = new HashMap<>();
    private Path file;
    private MinecraftServer server;
    private ExecutorService ioExecutor;
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);
    private long generation;
    private long queuedGeneration;
    private boolean available;
    private boolean closing;
    private String unavailableReason;

    public PlayerPriorityStore(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("priority store logger must not be null");
        }
        this.logger = logger;
    }

    /** Initializes this store for the current server world. */
    public synchronized void initialize(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server must not be null");
        }
        if (isInitialized() && !closing) {
            throw new IllegalStateException("Player priority store is already initialized");
        }

        Path nextFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("cobbleware-data")
                .resolve("priorities.json");
        try {
            Files.createDirectories(nextFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create Cobbleware priority directory " + nextFile.getParent(), exception);
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
        unavailableReason = null;

        try {
            if (Files.exists(file)) {
                players.putAll(decode(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException exception) {
            available = false;
            unavailableReason = "Could not load player execution priorities.";
            logger.error("Could not load player execution priorities from {}", file, exception);
        }
    }

    /** Returns whether priorities can currently be read and updated. */
    public synchronized boolean isAvailable() {
        requireInitialized();
        return available && !closing;
    }

    /** Returns a concise player-facing explanation when the store is unavailable. */
    public synchronized String unavailableReason() {
        requireInitialized();
        return unavailableReason == null ? "Player execution priorities are unavailable." : unavailableReason;
    }

    /** Returns a player's effective priorities, using the documented defaults when no override is stored. */
    public synchronized Priorities priorities(UUID playerId) {
        requireAvailable();
        requirePlayerId(playerId);
        return players.getOrDefault(playerId, DEFAULT_PRIORITIES);
    }

    /** Sets one of a player's durable priorities and returns both effective priorities. */
    public synchronized Priorities setPriority(UUID playerId, PriorityTarget target, int priority) {
        requireAvailable();
        requirePlayerId(playerId);
        if (target == null) {
            throw new IllegalArgumentException("priority target must not be null");
        }
        requirePriority(priority);

        Priorities previous = players.getOrDefault(playerId, DEFAULT_PRIORITIES);
        Priorities next = target == PriorityTarget.ONLINE
                ? new Priorities(priority, previous.offlinePriority())
                : new Priorities(previous.onlinePriority(), priority);
        if (!next.equals(previous) || !players.containsKey(playerId)) {
            players.put(playerId, next);
            generation += 1;
        }
        return next;
    }

    /** Queues an atomic write before Minecraft saves its worlds. */
    public synchronized void beforeSave() {
        requireInitialized();
        if (!available || closing) {
            return;
        }
        queueWrite();
    }

    /** Synchronously flushes the final priority snapshot during server shutdown. */
    @Override
    public void close() {
        Path target;
        Map<UUID, Priorities> snapshot;
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
                logger.error("Could not synchronously save player execution priorities to {}", target, exception);
            }
        }
        executor.shutdown();
    }

    static String encode(Map<UUID, Priorities> priorities) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", CURRENT_FORMAT_VERSION);
        JsonObject playerValues = new JsonObject();
        priorities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("onlinePriority", entry.getValue().onlinePriority());
                    value.addProperty("offlinePriority", entry.getValue().offlinePriority());
                    playerValues.add(entry.getKey().toString(), value);
                });
        root.add("players", playerValues);
        return JSON.toJson(root) + "\n";
    }

    static Map<UUID, Priorities> decode(String contents) {
        JsonElement rootElement = JsonParser.parseString(contents);
        if (!rootElement.isJsonObject()) {
            throw new JsonParseException("priority file root must be an object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        if (requiredInteger(root, "formatVersion") != CURRENT_FORMAT_VERSION) {
            throw new JsonParseException("unsupported priority file format version");
        }
        JsonElement playersElement = root.get("players");
        if (playersElement == null || !playersElement.isJsonObject()) {
            throw new JsonParseException("priority file players must be an object");
        }

        Map<UUID, Priorities> decoded = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry :
                playersElement.getAsJsonObject().entrySet()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("priority file contains an invalid player UUID", exception);
            }
            if (!entry.getValue().isJsonObject()) {
                throw new JsonParseException("priority record must be an object");
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            Priorities priorities;
            try {
                priorities = new Priorities(
                        requiredInteger(value, "onlinePriority"), requiredInteger(value, "offlinePriority"));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("priority record contains an out-of-range priority", exception);
            }
            if (decoded.put(playerId, priorities) != null) {
                throw new JsonParseException("priority file contains a duplicate player UUID");
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
            Thread thread = new Thread(runnable, "Cobbleware player-priority writer");
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
                                throw new PlayerPriorityWriteException(exception);
                            }
                        },
                        ioExecutor);
        writeTail.whenComplete((ignored, failure) -> completeWrite(queued, failure));
    }

    private void completeWrite(long completedGeneration, Throwable failure) {
        server.execute(() -> {
            synchronized (PlayerPriorityStore.this) {
                if (failure != null) {
                    logger.error("Could not save player execution priorities to {}", file, failure);
                    if (queuedGeneration == completedGeneration) {
                        queuedGeneration = 0;
                    }
                }
            }
        });
    }

    private void requireInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("Player priority store has not been initialized");
        }
    }

    private boolean isInitialized() {
        return file != null && server != null && ioExecutor != null;
    }

    private void requireAvailable() {
        requireInitialized();
        if (!available || closing) {
            throw new IllegalStateException(unavailableReason());
        }
    }

    private static void requirePlayerId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("player UUID must not be null");
        }
    }

    private static void requirePriority(int priority) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }
    }

    /** Selects the priority used when the player is online or offline. */
    public enum PriorityTarget {
        ONLINE,
        OFFLINE
    }

    /** The effective online and offline priorities for a player. */
    public record Priorities(int onlinePriority, int offlinePriority) {
        public Priorities {
            requirePriority(onlinePriority);
            requirePriority(offlinePriority);
        }
    }

    private static final class PlayerPriorityWriteException extends RuntimeException {
        private PlayerPriorityWriteException(IOException cause) {
            super(cause);
        }
    }
}
