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
import org.slf4j.Logger;

/** Stores durable Cobbleputer execution records in one atomic-replacement file per owner. */
public final class ExecutionStateStore implements AutoCloseable {
    public static final int CURRENT_EXECUTION_STATE_FORMAT_VERSION = 1;

    private final Path directory;
    private final MinecraftServer server;
    private final Logger logger;
    private final ExecutorService ioExecutor;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private boolean closing;

    public ExecutionStateStore(Path worldDirectory, MinecraftServer server, Logger logger) {
        if (worldDirectory == null || server == null || logger == null) {
            throw new IllegalArgumentException("execution state store dependencies must not be null");
        }
        directory = worldDirectory.resolve("cobbleware-data").resolve("executions");
        this.server = server;
        this.logger = logger;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Cobbleware execution-state writer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create Cobbleware execution-state directory " + directory, exception);
        }
    }

    /** Attaches the external execution record to a loaded block entity without changing chunk persistence. */
    public synchronized void attach(CobbleputerBlockEntity blockEntity) {
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
        if (candidate == null) {
            throw new IllegalArgumentException("candidate state must not be null");
        }
        return candidate.length <= stateSizeLimit(owner, cobbleputerId, ownerLimit);
    }

    /** Returns a read-only census of every durable execution-state byte length, grouped by owner. */
    public synchronized Map<UUID, Map<UUID, Long>> stateSizeCensus() {
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
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
            snapshot = Map.copyOf(players);
        }
        for (PlayerState player : snapshot.values()) {
            player.writeTail.join();
        }
        for (Map.Entry<UUID, PlayerState> entry : snapshot.entrySet()) {
            try {
                writeAtomically(fileFor(entry.getKey()), encode(entry.getValue().records));
            } catch (IOException exception) {
                logger.error("Could not synchronously save execution state for player {}", entry.getKey(), exception);
            }
        }
        ioExecutor.shutdown();
    }

    private PlayerState load(UUID owner) {
        PlayerState existing = players.get(owner);
        if (existing != null) {
            return existing;
        }
        Path file = fileFor(owner);
        Map<UUID, ExecutionRecord> records;
        try {
            records = Files.exists(file) ? decode(Files.readAllBytes(file)) : new HashMap<>();
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not load execution state for player {} from {}", owner, file, exception);
            return null;
        }
        PlayerState loaded = new PlayerState(records);
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
        byte[] bytes = encode(player.records);
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

    static byte[] encode(Map<UUID, ExecutionRecord> records) {
        PlayerExecutionStates.Builder file =
                PlayerExecutionStates.newBuilder().setFormatVersion(CURRENT_EXECUTION_STATE_FORMAT_VERSION);
        for (Map.Entry<UUID, ExecutionRecord> entry : records.entrySet()) {
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

    static Map<UUID, ExecutionRecord> decode(byte[] bytes) throws IOException {
        try {
            PlayerExecutionStates file = PlayerExecutionStates.parseFrom(bytes);
            if (file.getFormatVersion() != CURRENT_EXECUTION_STATE_FORMAT_VERSION) {
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
            return records;
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
        private final Map<CobbleputerBlockEntity, Long> awaitingMigrationCompletion = new HashMap<>();
        private long generation;
        private long queuedGeneration;
        private long persistedGeneration;
        private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);

        private PlayerState(Map<UUID, ExecutionRecord> records) {
            this.records = records;
        }
    }

    private static final class ExecutionStateWriteException extends RuntimeException {
        private ExecutionStateWriteException(IOException cause) {
            super(cause);
        }
    }
}
