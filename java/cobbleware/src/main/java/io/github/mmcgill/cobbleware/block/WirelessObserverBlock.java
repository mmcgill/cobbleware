package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** A colored, orientable observer endpoint. Its sensing and redstone behavior are added later. */
public final class WirelessObserverBlock extends Block implements EntityBlock, PolymerTexturedBlock {
    private final DyeColor color;

    public WirelessObserverBlock(DyeColor color, BlockBehaviour.Properties properties) {
        super(properties);
        this.color = color;
        registerDefaultState(stateDefinition.any().setValue(DirectionalBlock.FACING, Direction.NORTH));
    }

    public DyeColor color() {
        return color;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // This matches vanilla ObserverBlock: its front points in the placement look direction.
        return defaultBlockState().setValue(DirectionalBlock.FACING, facingForPlacement(context.getNearestLookingDirection()));
    }

    static Direction facingForPlacement(Direction nearestLookingDirection) {
        return nearestLookingDirection;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WirelessObserverBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DirectionalBlock.FACING);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return WirelessObserverPolymerModels.stateFor(color, state.getValue(DirectionalBlock.FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return getPolymerBlockState(state, context);
    }
}
