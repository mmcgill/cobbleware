package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.server.level.ServerLevel;

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
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess scheduledTickAccess,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            RandomSource random) {
        if (level instanceof ServerLevel serverLevel
                && neighborPos.equals(pos.relative(state.getValue(DirectionalBlock.FACING)))
                && serverLevel.getBlockEntity(pos) instanceof WirelessObserverBlockEntity observer
                && observer.hasObservedStateChange()) {
            ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
            if (executionDriver != null) {
                executionDriver.registerObserverChange(observer);
            }
        }
        return super.updateShape(state, level, scheduledTickAccess, pos, direction, neighborPos, neighborState, random);
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
