package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A placeholder Cobbleputer whose lifecycle state is represented by its block state. */
public final class CobbleputerBlock extends Block implements PolymerTexturedBlock {
    public static final EnumProperty<CobbleputerState> STATE = EnumProperty.create("state", CobbleputerState.class);

    public CobbleputerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STATE, CobbleputerState.UNINITIALIZED));
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(STATE, state.getValue(STATE).next()), Block.UPDATE_ALL);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STATE);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return CobbleputerPolymerModels.stateFor(state.getValue(STATE));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return getPolymerBlockState(state, context);
    }
}
