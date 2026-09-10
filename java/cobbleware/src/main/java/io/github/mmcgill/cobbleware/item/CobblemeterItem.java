package io.github.mmcgill.cobbleware.item;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
            return InteractionResult.PASS;
        }

        if (context.getLevel() instanceof ServerLevel level) {
            CobblemeterPairing.pair(
                    context.getItemInHand(),
                    GlobalPos.of(level.dimension(), context.getClickedPos().immutable()));
            context.getItemInHand().set(
                    DataComponents.ITEM_NAME,
                    Component.translatable("item.cobbleware.cobblemeter.paired_name"));
            if (context.getPlayer() != null) {
                context.getPlayer().sendSystemMessage(Component.translatable(
                        "item.cobbleware.cobblemeter.paired",
                        context.getClickedPos().getX(),
                        context.getClickedPos().getY(),
                        context.getClickedPos().getZ()));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
