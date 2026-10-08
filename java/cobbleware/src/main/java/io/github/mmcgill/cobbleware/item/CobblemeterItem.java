package io.github.mmcgill.cobbleware.item;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** An item that pairs itself with the Cobbleputer on which it is used. */
public final class CobblemeterItem extends SimplePolymerItem {
    public CobblemeterItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().getBlockState(context.getClickedPos()).is(CobblewareBlocks.COBBLEPUTER)) {
            return useOnCobbleputer(context);
        }
        if (CobblewareBlocks.isWirelessObserver(context.getLevel().getBlockState(context.getClickedPos()))) {
            return useOnObserver(context);
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult useOnCobbleputer(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(level.getBlockEntity(context.getClickedPos()) instanceof CobbleputerBlockEntity cobbleputer)) {
            return InteractionResult.SUCCESS;
        }
        ServerPlayer player = context.getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (!canPairWith(cobbleputer, player)) {
            sendMessage(player, "item.cobbleware.cobblemeter.cobbleputer_not_owned");
            return InteractionResult.SUCCESS;
        }
        pairMeter(
                context, GlobalPos.of(level.dimension(), context.getClickedPos().immutable()));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult useOnObserver(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(level.getBlockEntity(context.getClickedPos()) instanceof WirelessObserverBlockEntity observer)) {
            return InteractionResult.SUCCESS;
        }
        ServerPlayer player = context.getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        Optional<GlobalPos> meterTarget = CobblemeterPairing.getPairedCobbleputer(context.getItemInHand());
        Optional<GlobalPos> observerTarget = observer.pairedCobbleputer();
        if (meterTarget.isEmpty()) {
            pairUnpairedMeterFromObserver(context, level, observerTarget, player);
            return InteractionResult.SUCCESS;
        }
        if (observerTarget.isEmpty()) {
            pairObserver(context, level, observer, meterTarget.orElseThrow());
            return InteractionResult.SUCCESS;
        }
        if (meterTarget.orElseThrow().equals(observerTarget.orElseThrow())) {
            unpairObserver(level, observer, observerTarget.orElseThrow(), player);
        }
        return InteractionResult.SUCCESS;
    }

    private static void pairUnpairedMeterFromObserver(
            UseOnContext context, ServerLevel level, Optional<GlobalPos> observerTarget, ServerPlayer player) {
        if (observerTarget.isEmpty()) {
            sendMessage(player, "item.cobbleware.cobblemeter.observer_unpaired");
            return;
        }
        Optional<CobbleputerBlockEntity> target = loadedCobbleputer(level, observerTarget.orElseThrow());
        if (target.isEmpty()) {
            sendMessage(player, "item.cobbleware.cobblemeter.target_unavailable");
            return;
        }
        if (!canPairWith(target.orElseThrow(), player)) {
            sendMessage(player, "item.cobbleware.cobblemeter.cobbleputer_not_owned");
            return;
        }
        pairMeter(context, observerTarget.orElseThrow());
    }

    private static void pairObserver(
            UseOnContext context, ServerLevel level, WirelessObserverBlockEntity observer, GlobalPos target) {
        observer.setPairedCobbleputer(target);
        ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
        if (executionDriver != null) {
            executionDriver.observerPaired(ExecutionDriver.minecraft(level.getServer()), observer, null, target);
        }
        if (context.getPlayer() != null) {
            context.getPlayer()
                    .sendSystemMessage(Component.translatable(
                            "item.cobbleware.cobblemeter.observer_paired",
                            context.getClickedPos().getX(),
                            context.getClickedPos().getY(),
                            context.getClickedPos().getZ()));
        }
    }

    private static void unpairObserver(
            ServerLevel level, WirelessObserverBlockEntity observer, GlobalPos target, ServerPlayer player) {
        boolean isGamemaster = player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!isGamemaster) {
            Optional<CobbleputerBlockEntity> targetCobbleputer = loadedCobbleputer(level, target);
            if (targetCobbleputer.isEmpty()
                    || !canUnpairFrom(
                            targetCobbleputer.orElseThrow().ownerPlayerId().orElse(null),
                            player == null ? null : player.getUUID(),
                            false)) {
                sendMessage(player, "item.cobbleware.cobblemeter.observer_not_owned");
                return;
            }
        }
        if (observer.clearPairedCobbleputer().isEmpty()) {
            return;
        }
        ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
        if (executionDriver != null) {
            executionDriver.observerUnpaired(ExecutionDriver.minecraft(level.getServer()), observer, target);
        }
        sendMessage(player, "item.cobbleware.cobblemeter.observer_unpaired_success");
    }

    private static Optional<CobbleputerBlockEntity> loadedCobbleputer(ServerLevel level, GlobalPos target) {
        ServerLevel targetLevel = level.getServer().getLevel(target.dimension());
        if (targetLevel == null
                || !targetLevel.isLoaded(target.pos())
                || !(targetLevel.getBlockEntity(target.pos()) instanceof CobbleputerBlockEntity cobbleputer)) {
            return Optional.empty();
        }
        return Optional.of(cobbleputer);
    }

    private static void pairMeter(UseOnContext context, GlobalPos target) {
        CobblemeterPairing.pair(context.getItemInHand(), target);
        context.getItemInHand()
                .set(DataComponents.ITEM_NAME, Component.translatable("item.cobbleware.cobblemeter.paired_name"));
        if (context.getPlayer() != null) {
            context.getPlayer()
                    .sendSystemMessage(Component.translatable(
                            "item.cobbleware.cobblemeter.paired",
                            target.pos().getX(),
                            target.pos().getY(),
                            target.pos().getZ()));
        }
    }

    private static void sendMessage(ServerPlayer player, String key) {
        if (player != null) {
            player.sendSystemMessage(Component.translatable(key));
        }
    }

    /** Returns whether a player may pair a Cobblemeter with a Cobbleputer in the given lifecycle state. */
    static boolean canPairWith(
            CobbleputerState lifecycleState, UUID ownerPlayerId, UUID actingPlayerId, boolean hasGamemasterPermission) {
        return switch (lifecycleState) {
            case UNINITIALIZED -> true;
            case RUNNING, STOPPED, CRASHED ->
                hasGamemasterPermission || (actingPlayerId != null && actingPlayerId.equals(ownerPlayerId));
        };
    }

    /** Returns whether a player may unpair an observer from a Cobbleputer. */
    static boolean canUnpairFrom(UUID ownerPlayerId, UUID actingPlayerId, boolean hasGamemasterPermission) {
        return hasGamemasterPermission || (actingPlayerId != null && actingPlayerId.equals(ownerPlayerId));
    }

    private static boolean canPairWith(CobbleputerBlockEntity cobbleputer, ServerPlayer player) {
        return canPairWith(
                cobbleputer.getBlockState().getValue(CobbleputerBlock.STATE),
                cobbleputer.ownerPlayerId().orElse(null),
                player == null ? null : player.getUUID(),
                player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }
}
