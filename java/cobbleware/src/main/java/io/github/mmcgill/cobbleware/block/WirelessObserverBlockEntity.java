package io.github.mmcgill.cobbleware.block;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Durable pairing data for one placed Wireless Observer. */
public final class WirelessObserverBlockEntity extends BlockEntity {
    static final String OBSERVER_ID_TAG = "observer_id";
    static final String PAIRED_DIMENSION_TAG = "paired_dimension";
    static final String PAIRED_POSITION_TAG = "paired_pos";
    static final String OBSERVED_STATE_TAG = "observed_state";

    private UUID observerId;
    private GlobalPos pairedCobbleputer;
    private BlockState observedState;

    public WirelessObserverBlockEntity(BlockPos pos, BlockState state) {
        super(CobblewareBlockEntities.WIRELESS_OBSERVER, pos, state);
        observerId = UUID.randomUUID();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
            if (observedState == null && executionDriver != null) {
                executionDriver.registerObserverInitialization(this);
            }
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.activate(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
            if (executionDriver != null) {
                executionDriver.deactivate(this);
            }
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.deactivate(this);
            }
        }
        super.setRemoved();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof net.minecraft.server.level.ServerLevel) {
            ExecutionDriver executionDriver = CobblewareMod.executionDriverOrNull();
            if (executionDriver != null) {
                executionDriver.observerRemoved(this);
            }
        }
        super.preRemoveSideEffects(pos, state);
    }

    public Optional<GlobalPos> pairedCobbleputer() {
        return Optional.ofNullable(pairedCobbleputer);
    }

    /** Returns the durable identity of this placed Wireless Observer. */
    public UUID observerId() {
        return observerId;
    }

    /** Returns the position of the block observed by this endpoint. */
    public BlockPos observedBlockPos() {
        return worldPosition.relative(getBlockState().getValue(DirectionalBlock.FACING));
    }

    /** Returns whether the current faced state differs from the durable baseline. */
    public boolean hasObservedStateChange() {
        return level != null && observedState != null && !observedState.equals(level.getBlockState(observedBlockPos()));
    }

    /**
     * Captures and persists the current faced state, returning a change only when the
     * previous baseline was different. This intentionally advances the baseline even
     * when the caller later cannot deliver an event.
     */
    public Optional<ObservedBlockStateChange> takeObservedStateChange() {
        if (level == null) {
            return Optional.empty();
        }
        BlockState currentState = level.getBlockState(observedBlockPos());
        if (observedState == null) {
            observedState = currentState;
            setChanged();
            return Optional.empty();
        }
        if (observedState.equals(currentState)) {
            return Optional.empty();
        }
        ObservedBlockStateChange change = new ObservedBlockStateChange(observedBlockPos(), observedState, currentState);
        observedState = currentState;
        setChanged();
        return Optional.of(change);
    }

    /** Replaces the paired Cobbleputer and updates the loaded-observer index. */
    public Optional<GlobalPos> setPairedCobbleputer(GlobalPos target) {
        if (target == null) {
            throw new IllegalArgumentException("paired Cobbleputer must not be null");
        }
        GlobalPos previous = pairedCobbleputer;
        pairedCobbleputer = target;
        if (previous != null
                && !previous.equals(target)
                && level != null
                && !level.isClientSide()
                && getBlockState().getBlock() instanceof WirelessObserverBlock) {
            WirelessObserverBlock.setPower(level, worldPosition, getBlockState(), 0);
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.reindex(this);
            }
        }
        return Optional.ofNullable(previous);
    }

    /** Clears the paired Cobbleputer, disables output, and updates the loaded-observer index. */
    public Optional<GlobalPos> clearPairedCobbleputer() {
        GlobalPos previous = pairedCobbleputer;
        if (previous == null) {
            return Optional.empty();
        }
        pairedCobbleputer = null;
        if (level != null && !level.isClientSide() && getBlockState().getBlock() instanceof WirelessObserverBlock) {
            WirelessObserverBlock.setPower(level, worldPosition, getBlockState(), 0);
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.reindex(this);
            }
        }
        return Optional.of(previous);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(OBSERVER_ID_TAG, UUIDUtil.CODEC, observerId);
        if (pairedCobbleputer != null) {
            output.putString(
                    PAIRED_DIMENSION_TAG,
                    pairedCobbleputer.dimension().identifier().toString());
            output.putLong(PAIRED_POSITION_TAG, pairedCobbleputer.pos().asLong());
        }
        output.storeNullable(OBSERVED_STATE_TAG, BlockState.CODEC, observedState);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        observerId = input.read(OBSERVER_ID_TAG, UUIDUtil.CODEC)
                .orElseThrow(() -> new IllegalStateException("Missing required observer_id"));
        pairedCobbleputer = readPairing(input).orElse(null);
        observedState = input.read(OBSERVED_STATE_TAG, BlockState.CODEC).orElse(null);
    }

    static Optional<GlobalPos> readPairing(ValueInput input) {
        Optional<String> dimensionName = input.getString(PAIRED_DIMENSION_TAG);
        Optional<Long> packedPosition = input.getLong(PAIRED_POSITION_TAG);
        if (dimensionName.isEmpty() || packedPosition.isEmpty()) {
            return Optional.empty();
        }
        Identifier dimensionId = Identifier.tryParse(dimensionName.orElseThrow());
        if (dimensionId == null) {
            return Optional.empty();
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return Optional.of(GlobalPos.of(dimension, BlockPos.of(packedPosition.orElseThrow())));
    }

    /**
     * Initializes a missing baseline only after both positions are already loaded. This
     * must be called from normal server-tick work, never while this block entity is being
     * attached during chunk loading.
     */
    public boolean initializeObservedState() {
        if (observedState != null) {
            return true;
        }
        if (level == null || !level.isLoaded(observedBlockPos())) {
            return false;
        }
        observedState = level.getBlockState(observedBlockPos());
        setChanged();
        return true;
    }

    /** A before/after snapshot captured while draining one dirty observer. */
    public record ObservedBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {}
}
