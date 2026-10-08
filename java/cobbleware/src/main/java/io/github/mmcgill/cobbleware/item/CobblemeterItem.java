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
        if (!context.getLevel().getBlockState(context.getClickedPos()).is(CobblewareBlocks.COBBLEPUTER)) {
            if (!CobblewareBlocks.isWirelessObserver(context.getLevel().getBlockState(context.getClickedPos()))) {
                return InteractionResult.PASS;
            }
            if (context.getLevel() instanceof ServerLevel level) {
                Optional<GlobalPos> target = CobblemeterPairing.getPairedCobbleputer(context.getItemInHand());
                if (target.isEmpty()) {
                    if (context.getPlayer() != null) {
                        context.getPlayer()
                                .sendSystemMessage(
                                        Component.translatable("item.cobbleware.cobblemeter.observer_unpaired"));
                    }
                    return InteractionResult.SUCCESS;
                }
                if (level.getBlockEntity(context.getClickedPos()) instanceof WirelessObserverBlockEntity observer) {
                    GlobalPos newTarget = target.orElseThrow();
                    Optional<GlobalPos> previousTarget = observer.setPairedCobbleputer(newTarget);
                    ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
                    if (executionDriver != null) {
                        executionDriver.observerPaired(
                                ExecutionDriver.minecraft(level.getServer()),
                                observer,
                                previousTarget.orElse(null),
                                newTarget);
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
            }
            return InteractionResult.SUCCESS;
        }

        if (context.getLevel() instanceof ServerLevel level) {
            CobbleputerState lifecycleState =
                    context.getLevel().getBlockState(context.getClickedPos()).getValue(CobbleputerBlock.STATE);
            UUID ownerPlayerId =
                    level.getBlockEntity(context.getClickedPos()) instanceof CobbleputerBlockEntity cobbleputer
                            ? cobbleputer.ownerPlayerId().orElse(null)
                            : null;
            ServerPlayer player = context.getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            if (!canPairWith(
                    lifecycleState,
                    ownerPlayerId,
                    player == null ? null : player.getUUID(),
                    player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))) {
                if (player != null) {
                    player.sendSystemMessage(
                            Component.translatable("item.cobbleware.cobblemeter.cobbleputer_not_owned"));
                }
                return InteractionResult.SUCCESS;
            }
            CobblemeterPairing.pair(
                    context.getItemInHand(),
                    GlobalPos.of(level.dimension(), context.getClickedPos().immutable()));
            context.getItemInHand()
                    .set(DataComponents.ITEM_NAME, Component.translatable("item.cobbleware.cobblemeter.paired_name"));
            if (context.getPlayer() != null) {
                context.getPlayer()
                        .sendSystemMessage(Component.translatable(
                                "item.cobbleware.cobblemeter.paired",
                                context.getClickedPos().getX(),
                                context.getClickedPos().getY(),
                                context.getClickedPos().getZ()));
            }
        }
        return InteractionResult.SUCCESS;
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
}
