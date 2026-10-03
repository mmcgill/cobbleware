package io.github.mmcgill.cobbleware.execution;

import cobbleware.common.Uuid;
import cobbleware.internal.PlayerExecutionStates;
import cobbleware.internal.StoredExecutionState;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Stores durable Cobbleputer execution records in one atomic-replacement file per owner. */
public final class ExecutionStateStore implements AutoCloseable, ExecutionStateAccess {
    public static final int CURRENT_EXECUTION_STATE_FORMAT_VERSION = 2;
    private static final int BURST_CHAT_TOKEN_CAPACITY = 10;
    private static final int SLOW_CHAT_TOKEN_CAPACITY = 100;
    private static final long BURST_CHAT_TRANSFER_TICKS = 20L * 5L;
    private static final long SLOW_CHAT_REFILL_TICKS = 20L * 20L;

    private final Logger logger;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private Path directory;
    private MinecraftServer server;
    private ExecutorService ioExecutor;
    private boolean closing;

    public ExecutionStateStore(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("execution state store logger must not be null");
        }
        this.logger = logger;
    }

    /** Initializes this store for a server's current world. */
    public synchronized void initialize(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server must not be null");
        }
        if (isInitialized() && !closing) {
            throw new IllegalStateException("Execution state store is already initialized");
        }
        Path nextDirectory = server.getWorldPath(LevelResource.ROOT)
                .resolve("cobbleware-data")
                .resolve("executions");
        try {
            Files.createDirectories(nextDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create Cobbleware execution-state directory " + nextDirectory, exception);
        }
        directory = nextDirectory;
        this.server = server;
        ioExecutor = newIoExecutor();
        players.clear();
        closing = false;
    }

    private static ExecutorService newIoExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Cobbleware execution-state writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Attaches the external execution record to a loaded block entity without changing chunk persistence. */
    public synchronized void attach(CobbleputerBlockEntity blockEntity) {
        requireInitialized();
        if (closing || blockEntity == null) {
            return;
        }
        Optional<UUID> owner = blockEntity.ownerPlayerId();
        if (owner.isEmpty()) {
            return;
        }
        PlayerState player = load(owner.orElseThrow());
        if (player == null) {
            return;
        }
        UUID cobbleputerId = blockEntity.cobbleputerId();
        ExecutionRecord existing = player.records.get(cobbleputerId);
        if (blockEntity.hasLegacyExecutionState()) {
            if (existing == null) {
                ExecutionRecord legacy = blockEntity.executionRecord().orElse(null);
                if (legacy == null) {
                    return;
                }
                player.records.put(cobbleputerId, legacy);
                long generation = markDirty(player);
                player.awaitingMigrationCompletion.put(blockEntity, generation);
            } else {
                if (loadValidatedRecord(blockEntity, existing, owner.orElseThrow())) {
                    scheduleLegacyMigrationCompletion(blockEntity);
                }
            }
            return;
        }
        if (existing != null) {
            loadValidatedRecord(blockEntity, existing, owner.orElseThrow());
        } else {
            logger.error(
                    "Cobbleputer {} at {} refers to missing execution state for owner {}",
                    cobbleputerId,
                    blockEntity.getBlockPos().toShortString(),
                    owner.orElseThrow());
        }
    }

    private boolean loadValidatedRecord(CobbleputerBlockEntity blockEntity, ExecutionRecord record, UUID owner) {
        if (blockEntity.acceptsExecutionRecord(record)) {
            blockEntity.loadExecutionRecord(record);
            return true;
        }
        logger.error(
                "Execution state for Cobbleputer {} is incompatible with its lifecycle state at {} (owner {})",
                blockEntity.cobbleputerId(),
                blockEntity.getBlockPos().toShortString(),
                owner);
        return false;
    }

    /** Records the block entity's current execution state, removing a prior owner's stale record if needed. */
    public synchronized void update(CobbleputerBlockEntity blockEntity, UUID priorOwner) {
        requireInitialized();
        if (closing || blockEntity == null) {
            return;
        }
        Optional<UUID> owner = blockEntity.ownerPlayerId();
        if (owner.isEmpty()) {
            return;
        }
        UUID currentOwner = owner.orElseThrow();
        if (priorOwner != null && !priorOwner.equals(currentOwner)) {
            PlayerState previousPlayer = load(priorOwner);
            if (previousPlayer != null && previousPlayer.records.remove(blockEntity.cobbleputerId()) != null) {
                markDirty(previousPlayer);
            }
        }
        ExecutionRecord record = blockEntity.executionRecord().orElse(null);
        if (record == null) {
            return;
        }
        PlayerState player = load(currentOwner);
        if (player == null) {
            return;
        }
        player.records.put(blockEntity.cobbleputerId(), record);
        markDirty(player);
    }

    /** Removes the block entity's external execution record after physical Cobbleputer removal. */
    public synchronized void remove(CobbleputerBlockEntity blockEntity) {
        requireInitialized();
        if (closing || blockEntity == null) {
            return;
        }
        Optional<UUID> owner = blockEntity.ownerPlayerId();
        if (owner.isEmpty()) {
            return;
        }
        PlayerState player = load(owner.orElseThrow());
        if (player != null && player.records.remove(blockEntity.cobbleputerId()) != null) {
            markDirty(player);
        }
    }

    /**
     * Returns the largest state byte length this Cobbleputer may next store without exceeding its owner's cap.
     * The result treats this Cobbleputer's current record as replaceable and all other durable records as fixed.
     */
    public synchronized long stateSizeLimit(UUID owner, UUID cobbleputerId, long ownerLimit) {
        requireInitialized();
        if (owner == null || cobbleputerId == null || ownerLimit < 0) {
            throw new IllegalArgumentException("owner, Cobbleputer id, and non-negative limit are required");
        }
        PlayerState player = load(owner);
        if (player == null) {
            return 0;
        }
        long otherBytes = 0;
        for (Map.Entry<UUID, ExecutionRecord> entry : player.records.entrySet()) {
            if (entry.getKey().equals(cobbleputerId)) {
                continue;
            }
            int length = entry.getValue().executionState().length;
            if (Long.MAX_VALUE - otherBytes < length) {
                return 0;
            }
            otherBytes += length;
        }
        return otherBytes >= ownerLimit ? 0 : ownerLimit - otherBytes;
    }

    /** Returns whether replacing one durable record's state with {@code candidate} fits the owner's cap. */
    public synchronized boolean fitsStateUpdate(UUID owner, UUID cobbleputerId, byte[] candidate, long ownerLimit) {
        requireInitialized();
        if (candidate == null) {
            throw new IllegalArgumentException("candidate state must not be null");
        }
        return candidate.length <= stateSizeLimit(owner, cobbleputerId, ownerLimit);
    }

    @Override
    public synchronized ChatTokens chatTokens(UUID owner, long gameTime) {
        requireInitialized();
        if (owner == null || gameTime < 0) {
            throw new IllegalArgumentException("owner and non-negative game time are required");
        }
        if (closing) {
            return new ChatTokens(0, 0);
        }
        PlayerState player = load(owner);
        if (player == null) {
            return new ChatTokens(0, 0);
        }
        if (player.chatTokens.advance(gameTime)) {
            markDirty(player);
        }
        return player.chatTokens.snapshot();
    }

    @Override
    public synchronized boolean tryConsumeBurstChatToken(UUID owner, long gameTime) {
        requireInitialized();
        if (owner == null || gameTime < 0) {
            throw new IllegalArgumentException("owner and non-negative game time are required");
        }
        if (closing) {
            return false;
        }
        PlayerState player = load(owner);
        if (player == null) {
            return false;
        }
        boolean changed = player.chatTokens.advance(gameTime);
        boolean consumed = player.chatTokens.tryConsumeBurst();
        if (changed || consumed) {
            markDirty(player);
        }
        return consumed;
    }

    /** Returns an immutable snapshot of each loaded owner's current chat-token buckets. */
    @Override
    public synchronized Map<UUID, ChatTokens> chatTokenCensus(long gameTime) {
        requireInitialized();
        if (gameTime < 0) {
            throw new IllegalArgumentException("non-negative game time is required");
        }
        if (closing) {
            return Map.of();
        }
        Map<UUID, ChatTokens> census = new HashMap<>();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState player = entry.getValue();
            if (player.chatTokens.advance(gameTime)) {
                markDirty(player);
            }
            census.put(entry.getKey(), player.chatTokens.snapshot());
        }
        return Map.copyOf(census);
    }

    /** Returns a read-only census of every durable execution-state byte length, grouped by owner. */
    public synchronized Map<UUID, Map<UUID, Long>> stateSizeCensus() {
        requireInitialized();
        Map<UUID, Map<UUID, Long>> census = new HashMap<>();
        for (Map.Entry<UUID, PlayerState> player : players.entrySet()) {
            Map<UUID, Long> records = new HashMap<>();
            for (Map.Entry<UUID, ExecutionRecord> record :
                    player.getValue().records.entrySet()) {
                records.put(record.getKey(), (long) record.getValue().executionState().length);
            }
            census.put(player.getKey(), Map.copyOf(records));
        }
        return Map.copyOf(census);
    }

    /** Queues asynchronous writes of each changed owner file before Minecraft saves its worlds. */
    public synchronized void beforeSave() {
        requireInitialized();
        if (closing) {
            return;
        }
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            queueWrite(entry.getKey(), entry.getValue());
        }
    }

    /** Writes all loaded player states before the server exits. */
    @Override
    public void close() {
        Map<UUID, PlayerState> snapshot;
        ExecutorService executor;
        synchronized (this) {
            requireInitialized();
            if (closing) {
                return;
            }
            closing = true;
            snapshot = Map.copyOf(players);
            executor = ioExecutor;
        }
        for (PlayerState player : snapshot.values()) {
            player.writeTail.join();
        }
        for (Map.Entry<UUID, PlayerState> entry : snapshot.entrySet()) {
            try {
                writeAtomically(fileFor(entry.getKey()), encode(entry.getValue()));
            } catch (IOException exception) {
                logger.error("Could not synchronously save execution state for player {}", entry.getKey(), exception);
            }
        }
        executor.shutdown();
    }

    private void requireInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("Execution state store has not been initialized");
        }
    }

    private boolean isInitialized() {
        return directory != null && server != null && ioExecutor != null;
    }

    private PlayerState load(UUID owner) {
        PlayerState existing = players.get(owner);
        if (existing != null) {
            return existing;
        }
        Path file = fileFor(owner);
        DecodedPlayerState decoded;
        try {
            decoded = Files.exists(file)
                    ? decodePlayerState(Files.readAllBytes(file))
                    : new DecodedPlayerState(new HashMap<>(), ChatTokenBucket.uninitialized());
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not load execution state for player {} from {}", owner, file, exception);
            return null;
        }
        PlayerState loaded = new PlayerState(decoded.records(), decoded.chatTokens());
        players.put(owner, loaded);
        return loaded;
    }

    private long markDirty(PlayerState player) {
        player.generation += 1;
        return player.generation;
    }

    private void queueWrite(UUID owner, PlayerState player) {
        if (player.generation <= player.queuedGeneration) {
            return;
        }
        long generation = player.generation;
        byte[] bytes = encode(player);
        player.queuedGeneration = generation;
        player.writeTail = player.writeTail
                .handle((ignored, failure) -> null)
                .thenRunAsync(
                        () -> {
                            try {
                                writeAtomically(fileFor(owner), bytes);
                            } catch (IOException exception) {
                                throw new ExecutionStateWriteException(exception);
                            }
                        },
                        ioExecutor);
        player.writeTail.whenComplete((ignored, failure) -> completeWrite(owner, player, generation, failure));
    }

    private void completeWrite(UUID owner, PlayerState player, long generation, Throwable failure) {
        server.execute(() -> {
            synchronized (ExecutionStateStore.this) {
                if (failure != null) {
                    logger.error("Could not save execution state for player {}", owner, failure);
                    if (player.queuedGeneration == generation) {
                        player.queuedGeneration = 0;
                    }
                    return;
                }
                player.persistedGeneration = Math.max(player.persistedGeneration, generation);
                var migrations = player.awaitingMigrationCompletion.entrySet().iterator();
                while (migrations.hasNext()) {
                    Map.Entry<CobbleputerBlockEntity, Long> migration = migrations.next();
                    if (migration.getValue() <= generation) {
                        CobbleputerBlockEntity blockEntity = migration.getKey();
                        migrations.remove();
                        scheduleLegacyMigrationCompletion(blockEntity);
                    }
                }
            }
        });
    }

    private void scheduleLegacyMigrationCompletion(CobbleputerBlockEntity blockEntity) {
        server.execute(() -> {
            if (blockEntity.isRemoved()
                    || !(blockEntity.getLevel() instanceof ServerLevel level)
                    || !level.isLoaded(blockEntity.getBlockPos())) {
                return;
            }
            blockEntity.completeLegacyMigration();
        });
    }

    private Path fileFor(UUID owner) {
        return directory.resolve(owner.toString().toLowerCase(java.util.Locale.ROOT) + ".pb");
    }

    static byte[] encode(PlayerState player) {
        PlayerExecutionStates.Builder file = PlayerExecutionStates.newBuilder()
                .setFormatVersion(player.chatTokens.isInitialized() ? CURRENT_EXECUTION_STATE_FORMAT_VERSION : 1);
        if (player.chatTokens.isInitialized()) {
            file.setBurstChatTokens(player.chatTokens.burstTokens)
                    .setSlowChatTokens(player.chatTokens.slowTokens)
                    .setNextSlowChatRefillTick(player.chatTokens.nextSlowRefillTick)
                    .setNextBurstChatTransferTick(player.chatTokens.nextBurstTransferTick);
        }
        for (Map.Entry<UUID, ExecutionRecord> entry : player.records.entrySet()) {
            ExecutionRecord record = entry.getValue();
            StoredExecutionState.Builder stored = StoredExecutionState.newBuilder()
                    .setCobbleputerId(protoUuid(entry.getKey()))
                    .setOwnerPlayerName(record.ownerPlayerName())
                    .setExecutionState(com.google.protobuf.ByteString.copyFrom(record.executionState()))
                    .setNextEventSequenceNum(record.nextEventSequenceNum())
                    .setEffectSequenceNum(record.effectSequenceNum())
                    .setWasRunningWhenUnloaded(record.wasRunningWhenUnloaded());
            if (record.executionId() != null) {
                stored.setExecutionId(protoUuid(record.executionId()));
            }
            if (record.stoppedAtTick() != null) {
                stored.setStoppedAtTick(record.stoppedAtTick());
            }
            file.addExecutions(stored);
        }
        return file.build().toByteArray();
    }

    static byte[] encode(Map<UUID, ExecutionRecord> records) {
        return encode(new PlayerState(
                new HashMap<>(records),
                ChatTokenBucket.persisted(
                        BURST_CHAT_TOKEN_CAPACITY,
                        SLOW_CHAT_TOKEN_CAPACITY,
                        SLOW_CHAT_REFILL_TICKS,
                        BURST_CHAT_TRANSFER_TICKS)));
    }

    static Map<UUID, ExecutionRecord> decode(byte[] bytes) throws IOException {
        return decodePlayerState(bytes).records();
    }

    private static DecodedPlayerState decodePlayerState(byte[] bytes) throws IOException {
        try {
            PlayerExecutionStates file = PlayerExecutionStates.parseFrom(bytes);
            if (file.getFormatVersion() != 1 && file.getFormatVersion() != CURRENT_EXECUTION_STATE_FORMAT_VERSION) {
                throw new IOException("Unsupported execution state format version " + file.getFormatVersion());
            }
            Map<UUID, ExecutionRecord> records = new HashMap<>();
            for (StoredExecutionState stored : file.getExecutionsList()) {
                if (!stored.hasCobbleputerId()) {
                    throw new IOException("Execution state record is missing cobbleputer_id");
                }
                UUID cobbleputerId = javaUuid(stored.getCobbleputerId());
                UUID executionId = stored.hasExecutionId() ? javaUuid(stored.getExecutionId()) : null;
                Long stoppedAtTick = stored.hasStoppedAtTick() ? stored.getStoppedAtTick() : null;
                ExecutionRecord record = new ExecutionRecord(
                        executionId,
                        stored.getOwnerPlayerName(),
                        stored.getExecutionState().toByteArray(),
                        stored.getNextEventSequenceNum(),
                        stored.getEffectSequenceNum(),
                        stoppedAtTick,
                        stored.getWasRunningWhenUnloaded());
                if (!record.isValid() || records.put(cobbleputerId, record) != null) {
                    throw new IOException("Execution state file contains an invalid or duplicate record");
                }
            }
            ChatTokenBucket chatTokens = file.getFormatVersion() == 1
                    ? ChatTokenBucket.uninitialized()
                    : ChatTokenBucket.persisted(
                            file.getBurstChatTokens(),
                            file.getSlowChatTokens(),
                            file.getNextSlowChatRefillTick(),
                            file.getNextBurstChatTransferTick());
            if (file.getFormatVersion() != 1 && !chatTokens.isValid()) {
                throw new IOException("Execution state file contains invalid chat token bucket data");
            }
            return new DecodedPlayerState(records, chatTokens);
        } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
            throw new IOException("Could not decode execution state protobuf", exception);
        }
    }

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary =
                Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic replacement is not supported for " + target, exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Uuid protoUuid(UUID uuid) {
        return Uuid.newBuilder()
                .setHigh(uuid.getMostSignificantBits())
                .setLow(uuid.getLeastSignificantBits())
                .build();
    }

    private static UUID javaUuid(Uuid uuid) {
        return new UUID(uuid.getHigh(), uuid.getLow());
    }

    private record DecodedPlayerState(Map<UUID, ExecutionRecord> records, ChatTokenBucket chatTokens) {}

    static final class ChatTokenBucket {
        private int burstTokens;
        private int slowTokens;
        private long nextSlowRefillTick;
        private long nextBurstTransferTick;

        private ChatTokenBucket(int burstTokens, int slowTokens, long nextSlowRefillTick, long nextBurstTransferTick) {
            this.burstTokens = burstTokens;
            this.slowTokens = slowTokens;
            this.nextSlowRefillTick = nextSlowRefillTick;
            this.nextBurstTransferTick = nextBurstTransferTick;
        }

        static ChatTokenBucket uninitialized() {
            return new ChatTokenBucket(BURST_CHAT_TOKEN_CAPACITY, SLOW_CHAT_TOKEN_CAPACITY, 0, 0);
        }

        static ChatTokenBucket persisted(
                int burstTokens, int slowTokens, long nextSlowRefillTick, long nextBurstTransferTick) {
            return new ChatTokenBucket(burstTokens, slowTokens, nextSlowRefillTick, nextBurstTransferTick);
        }

        boolean isValid() {
            return burstTokens >= 0
                    && burstTokens <= BURST_CHAT_TOKEN_CAPACITY
                    && slowTokens >= 0
                    && slowTokens <= SLOW_CHAT_TOKEN_CAPACITY
                    && nextSlowRefillTick > 0
                    && nextBurstTransferTick > 0;
        }

        boolean isInitialized() {
            return nextSlowRefillTick != 0 || nextBurstTransferTick != 0;
        }

        boolean advance(long gameTime) {
            if (nextSlowRefillTick == 0 && nextBurstTransferTick == 0) {
                nextSlowRefillTick = addTicks(gameTime, SLOW_CHAT_REFILL_TICKS);
                nextBurstTransferTick = addTicks(gameTime, BURST_CHAT_TRANSFER_TICKS);
                return true;
            }
            boolean changed = false;
            while (nextSlowRefillTick <= gameTime || nextBurstTransferTick <= gameTime) {
                if (nextSlowRefillTick <= nextBurstTransferTick) {
                    slowTokens = Math.min(SLOW_CHAT_TOKEN_CAPACITY, slowTokens + 1);
                    nextSlowRefillTick = addTicks(nextSlowRefillTick, SLOW_CHAT_REFILL_TICKS);
                } else if (burstTokens < BURST_CHAT_TOKEN_CAPACITY && slowTokens > 0) {
                    slowTokens -= 1;
                    burstTokens += 1;
                    nextBurstTransferTick = addTicks(nextBurstTransferTick, BURST_CHAT_TRANSFER_TICKS);
                } else {
                    nextBurstTransferTick = addTicks(nextBurstTransferTick, BURST_CHAT_TRANSFER_TICKS);
                }
                changed = true;
            }
            return changed;
        }

        boolean tryConsumeBurst() {
            if (burstTokens == 0) {
                return false;
            }
            burstTokens -= 1;
            return true;
        }

        ChatTokens snapshot() {
            return new ChatTokens(burstTokens, slowTokens);
        }

        private static long addTicks(long tick, long delta) {
            return tick > Long.MAX_VALUE - delta ? Long.MAX_VALUE : tick + delta;
        }
    }

    /** Immutable copy of the durable state stored for one Cobbleputer. */
    public record ExecutionRecord(
            UUID executionId,
            String ownerPlayerName,
            byte[] executionState,
            long nextEventSequenceNum,
            long effectSequenceNum,
            Long stoppedAtTick,
            boolean wasRunningWhenUnloaded) {
        public ExecutionRecord {
            executionState = executionState == null ? null : Arrays.copyOf(executionState, executionState.length);
        }

        @Override
        public byte[] executionState() {
            return executionState == null ? null : Arrays.copyOf(executionState, executionState.length);
        }

        boolean isValid() {
            boolean validCrashed = executionId == null
                    && ownerPlayerName != null
                    && !ownerPlayerName.isEmpty()
                    && executionState != null
                    && nextEventSequenceNum >= 1
                    && effectSequenceNum >= 0
                    && stoppedAtTick == null;
            boolean validActive = executionId != null
                    && executionState != null
                    && nextEventSequenceNum >= 1
                    && effectSequenceNum >= 0
                    && (stoppedAtTick == null || stoppedAtTick >= 0);
            return validCrashed || validActive;
        }
    }

    private static final class PlayerState {
        private final Map<UUID, ExecutionRecord> records;
        private final ChatTokenBucket chatTokens;
        private final Map<CobbleputerBlockEntity, Long> awaitingMigrationCompletion = new HashMap<>();
        private long generation;
        private long queuedGeneration;
        private long persistedGeneration;
        private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);

        private PlayerState(Map<UUID, ExecutionRecord> records, ChatTokenBucket chatTokens) {
            this.records = records;
            this.chatTokens = chatTokens;
        }
    }

    private static final class ExecutionStateWriteException extends RuntimeException {
        private ExecutionStateWriteException(IOException cause) {
            super(cause);
        }
    }
}
