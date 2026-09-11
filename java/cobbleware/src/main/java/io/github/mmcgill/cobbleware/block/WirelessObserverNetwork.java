package io.github.mmcgill.cobbleware.block;

import io.github.mmcgill.cobbleware.item.CobblemeterPairing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Indexes loaded observer pairings and draws their paths for players holding a paired Cobblemeter. */
public final class WirelessObserverNetwork {
    private static final int VISUALIZATION_INTERVAL_TICKS = 4;
    private static final double PARTICLE_SPACING = 0.5;
    private static final int MAX_PARTICLES_PER_PATH = 128;

    private final Map<GlobalPos, Set<WirelessObserverBlockEntity>> observersByTarget = new HashMap<>();
    private final Map<WirelessObserverBlockEntity, GlobalPos> targetsByObserver = new IdentityHashMap<>();

    public WirelessObserverNetwork() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    public void activate(WirelessObserverBlockEntity observer) {
        reindex(observer);
    }

    public void deactivate(WirelessObserverBlockEntity observer) {
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
        });
    }

    /** Applies a power level only to a loaded observer still paired to the emitting Cobbleputer. */
    public boolean setRedstonePower(GlobalPos target, UUID observerId, int powerLevel) {
        Set<WirelessObserverBlockEntity> indexedObservers = observersByTarget.get(target);
        if (indexedObservers == null) {
            return false;
        }
        for (WirelessObserverBlockEntity observer : List.copyOf(indexedObservers)) {
            if (observer.isRemoved()
                    || !observerId.equals(observer.observerId())
                    || !observer.pairedCobbleputer().filter(target::equals).isPresent()
                    || !(observer.getLevel() instanceof ServerLevel level)
                    || !(observer.getBlockState().getBlock() instanceof WirelessObserverBlock wirelessObserver)) {
                continue;
            }
            return WirelessObserverBlock.setPower(
                    level, observer.getBlockPos(), observer.getBlockState(), powerLevel);
        }
        return false;
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

    private void tick(MinecraftServer server) {
        if (server.overworld().getGameTime() % VISUALIZATION_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pairedMeterTarget(player).ifPresent(target -> drawPaths(player, target));
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
            if (observer.isRemoved() || observer.getLevel() != level
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
        DustParticleOptions particle = new DustParticleOptions(wirelessObserver.color().getTextureDiffuseColor(), 1.0F);
        for (int index = 0; index < points; index++) {
            double progress = points == 1 ? 0.5 : (double) index / (points - 1);
            Vec3 point = start.lerp(end, progress);
            level.sendParticles(player, particle, false, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
    }
}
