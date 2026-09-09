package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A Cobbleputer whose lifecycle state is visible in its block state. */
public final class CobbleputerBlock extends Block implements EntityBlock, PolymerTexturedBlock {
    public static final EnumProperty<CobbleputerState> STATE = EnumProperty.create("state", CobbleputerState.class);

    public CobbleputerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STATE, CobbleputerState.UNINITIALIZED));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CobbleputerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult) {
        CobbleputerState currentState = state.getValue(STATE);
        CobbleputerState nextState = switch (currentState) {
            case RUNNING -> CobbleputerState.STOPPED;
            case STOPPED -> CobbleputerState.RUNNING;
            case UNINITIALIZED, CRASHED -> null;
        };
        if (nextState == null) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CobbleputerBlockEntity blockEntity) {
            ExecutionDriver executionDriver = CobblewareMod.executionDriver();
            if (nextState == CobbleputerState.STOPPED) {
                executionDriver.stop(serverLevel, pos, state, blockEntity);
            } else {
                executionDriver.resume(serverLevel, pos, state, blockEntity);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Updates and logs a Cobbleputer lifecycle transition. */
    public static boolean setLifecycleState(
            Level level,
            BlockPos pos,
            BlockState currentBlockState,
            CobbleputerState nextState) {
        CobbleputerState previousState = currentBlockState.getValue(STATE);
        if (previousState == nextState) {
            return false;
        }
        if (!level.setBlock(pos, currentBlockState.setValue(STATE, nextState), Block.UPDATE_ALL)) {
            return false;
        }
        CobblewareMod.LOGGER.info(
                "Cobbleputer at {} changed state from {} to {}",
                pos.toShortString(),
                previousState.getSerializedName(),
                nextState.getSerializedName());
        return true;
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
