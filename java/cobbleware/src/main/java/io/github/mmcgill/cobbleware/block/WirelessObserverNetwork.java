package io.github.mmcgill.cobbleware.block;

import io.github.mmcgill.cobbleware.item.CobblemeterPairing;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Indexes loaded observer pairings and draws their paths for players holding a paired Cobblemeter. */
public final class WirelessObserverNetwork {
    private static final int VISUALIZATION_INTERVAL_TICKS = 4;
    private static final double PARTICLE_SPACING = 0.5;
    private static final int MAX_PARTICLES_PER_PATH = 128;

    private final Map<GlobalPos, Set<WirelessObserverBlockEntity>> observersByTarget = new HashMap<>();
    private final Map<WirelessObserverBlockEntity, GlobalPos> targetsByObserver = new IdentityHashMap<>();
    private final Set<WirelessObserverBlockEntity> pendingPairingValidations =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public WirelessObserverNetwork() {}

    public void activate(WirelessObserverBlockEntity observer) {
        reindex(observer);
    }

    public void deactivate(WirelessObserverBlockEntity observer) {
        pendingPairingValidations.remove(observer);
        GlobalPos previous = targetsByObserver.remove(observer);
        if (previous != null) {
            removeFromTarget(previous, observer);
        }
    }

    /** Replaces the observer's loaded index entry with its current durable pairing. */
    public void reindex(WirelessObserverBlockEntity observer) {
        deactivate(observer);
        observer.pairedCobbleputer().ifPresent(target -> {
            targetsByObserver.put(observer, target);
            observersByTarget
                    .computeIfAbsent(target, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(observer);
            pendingPairingValidations.add(observer);
        });
    }

    /** Schedules validation of loaded observers whose targets lie in a newly loaded chunk. */
    public void targetChunkLoaded(ServerLevel level, ChunkPos chunkPos) {
        for (Map.Entry<GlobalPos, Set<WirelessObserverBlockEntity>> entry : observersByTarget.entrySet()) {
            if (targetIsInChunk(entry.getKey(), level.dimension(), chunkPos)) {
                pendingPairingValidations.addAll(entry.getValue());
            }
        }
    }

    /** Unpairs every loaded observer that is still paired to {@code target}. */
    public void unpairLoadedObserversFor(GlobalPos target) {
        Set<WirelessObserverBlockEntity> indexedObservers = observersByTarget.get(target);
        if (indexedObservers == null) {
            return;
        }
        for (WirelessObserverBlockEntity observer : List.copyOf(indexedObservers)) {
            if (observer.isRemoved()
                    || !(observer.getLevel() instanceof ServerLevel)
                    || !observer.pairedCobbleputer().filter(target::equals).isPresent()) {
                removeIndexedObserver(target, observer);
                continue;
            }
            observer.clearPairedCobbleputer();
        }
    }

    /** Applies a power level to every matching loaded observer still paired to the emitting Cobbleputer. */
    public boolean setRedstonePower(
            GlobalPos target, Optional<UUID> observerId, Optional<DyeColor> observerColor, int powerLevel) {
        Set<WirelessObserverBlockEntity> indexedObservers = observersByTarget.get(target);
        if (indexedObservers == null) {
            return false;
        }
        boolean changed = false;
        for (WirelessObserverBlockEntity observer : List.copyOf(indexedObservers)) {
            if (observer.isRemoved()
                    || !observer.pairedCobbleputer().filter(target::equals).isPresent()
                    || !(observer.getLevel() instanceof ServerLevel level)
                    || !(observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
                continue;
            }
            if (!matchesRedstonePowerTarget(
                    observer.observerId(), wirelessObserver.color(), observerId, observerColor)) {
                continue;
            }
            changed |=
                    WirelessObserverBlock.setPower(level, observer.getBlockPos(), observer.getBlockState(), powerLevel);
        }
        return changed;
    }

    /** Returns whether a loaded observer paired to {@code target} is within range in {@code level}. */
    public boolean hasPairedObserverNear(GlobalPos target, Level level, Vec3 position, double rangeSquared) {
        if (target == null || level == null || position == null || rangeSquared < 0) {
            throw new IllegalArgumentException("target, level, position, and non-negative range must be provided");
        }
        Set<WirelessObserverBlockEntity> indexedObservers = observersByTarget.get(target);
        if (indexedObservers == null) {
            return false;
        }
        for (WirelessObserverBlockEntity observer : List.copyOf(indexedObservers)) {
            if (observer.isRemoved()
                    || observer.getLevel() != level
                    || !observer.pairedCobbleputer().filter(target::equals).isPresent()) {
                continue;
            }
            if (position.distanceToSqr(Vec3.atCenterOf(observer.getBlockPos())) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesRedstonePowerTarget(
            UUID observerId, DyeColor observerColor, Optional<UUID> targetObserverId, Optional<DyeColor> targetColor) {
        return targetObserverId.map(observerId::equals).orElse(true)
                && targetColor.map(observerColor::equals).orElse(true);
    }

    static boolean targetIsInChunk(
            GlobalPos target, net.minecraft.resources.ResourceKey<Level> dimension, ChunkPos chunkPos) {
        return target.dimension().equals(dimension)
                && target.pos().getX() >> 4 == chunkPos.x()
                && target.pos().getZ() >> 4 == chunkPos.z();
    }

    private void removeIndexedObserver(GlobalPos target, WirelessObserverBlockEntity observer) {
        pendingPairingValidations.remove(observer);
        if (target.equals(targetsByObserver.get(observer))) {
            targetsByObserver.remove(observer);
        }
        removeFromTarget(target, observer);
    }

    private void removeFromTarget(GlobalPos target, WirelessObserverBlockEntity observer) {
        Set<WirelessObserverBlockEntity> observers = observersByTarget.get(target);
        if (observers == null) {
            return;
        }
        observers.remove(observer);
        if (observers.isEmpty()) {
            observersByTarget.remove(target);
        }
    }

    /** Per-tick processing of the Wirless Observer network. */
    public void tick(MinecraftServer server) {
        validatePairings(server);
        if (server.overworld().getGameTime() % VISUALIZATION_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pairedMeterTarget(player).ifPresent(target -> drawPaths(player, target));
        }
    }

    private void validatePairings(MinecraftServer server) {
        if (pendingPairingValidations.isEmpty()) {
            return;
        }
        List<WirelessObserverBlockEntity> observers = List.copyOf(pendingPairingValidations);
        pendingPairingValidations.clear();
        for (WirelessObserverBlockEntity observer : observers) {
            if (observer.isRemoved() || !(observer.getLevel() instanceof ServerLevel)) {
                continue;
            }
            Optional<GlobalPos> pairedTarget = observer.pairedCobbleputer();
            if (pairedTarget.isEmpty()) {
                continue;
            }
            GlobalPos target = pairedTarget.orElseThrow();
            ServerLevel targetLevel = server.getLevel(target.dimension());
            if (targetLevel == null) {
                observer.clearPairedCobbleputer();
                continue;
            }
            if (!targetLevel.isLoaded(target.pos())) {
                continue;
            }
            if (!(targetLevel.getBlockEntity(target.pos()) instanceof CobbleputerBlockEntity)) {
                observer.clearPairedCobbleputer();
            }
        }
    }

    private Optional<GlobalPos> pairedMeterTarget(ServerPlayer player) {
        Optional<GlobalPos> mainHandTarget = meterTarget(player.getMainHandItem());
        return mainHandTarget.isPresent() ? mainHandTarget : meterTarget(player.getOffhandItem());
    }

    private Optional<GlobalPos> meterTarget(ItemStack stack) {
        return stack.getItem() instanceof io.github.mmcgill.cobbleware.item.CobblemeterItem
                ? CobblemeterPairing.getPairedCobbleputer(stack)
                : Optional.empty();
    }

    private void drawPaths(ServerPlayer player, GlobalPos target) {
        Level playerLevel = player.level();
        if (!(playerLevel instanceof ServerLevel level) || !level.dimension().equals(target.dimension())) {
            return;
        }
        Set<WirelessObserverBlockEntity> indexedObservers = observersByTarget.get(target);
        if (indexedObservers == null) {
            return;
        }
        for (WirelessObserverBlockEntity observer : List.copyOf(indexedObservers)) {
            if (observer.isRemoved()
                    || observer.getLevel() != level
                    || !observer.pairedCobbleputer().filter(target::equals).isPresent()
                    || !(observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
                continue;
            }
            drawPath(level, player, target, observer, wirelessObserver);
        }
    }

    private static void drawPath(
            ServerLevel level,
            ServerPlayer player,
            GlobalPos target,
            WirelessObserverBlockEntity observer,
            WirelessObserverBlock wirelessObserver) {
        Vec3 start = Vec3.atCenterOf(target.pos());
        Vec3 end = Vec3.atCenterOf(observer.getBlockPos());
        double distance = start.distanceTo(end);
        int points = Math.min(MAX_PARTICLES_PER_PATH, Math.max(1, (int) Math.ceil(distance / PARTICLE_SPACING)));
        DustParticleOptions particle =
                new DustParticleOptions(wirelessObserver.color().getTextureDiffuseColor(), 1.0F);
        for (int index = 0; index < points; index++) {
            double progress = points == 1 ? 0.5 : (double) index / (points - 1);
            Vec3 point = start.lerp(end, progress);
            level.sendParticles(player, particle, false, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
    }
}
