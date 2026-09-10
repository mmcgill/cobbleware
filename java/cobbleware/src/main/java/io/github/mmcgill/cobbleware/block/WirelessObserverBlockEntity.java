package io.github.mmcgill.cobbleware.block;

import io.github.mmcgill.cobbleware.CobblewareMod;
import io.github.mmcgill.cobbleware.registry.CobblewareBlockEntities;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Durable pairing data for one placed Wireless Observer. */
public final class WirelessObserverBlockEntity extends BlockEntity {
    static final String PAIRED_DIMENSION_TAG = "paired_dimension";
    static final String PAIRED_POSITION_TAG = "paired_pos";

    private GlobalPos pairedCobbleputer;

    public WirelessObserverBlockEntity(BlockPos pos, BlockState state) {
        super(CobblewareBlockEntities.WIRELESS_OBSERVER, pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.activate(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.deactivate(this);
            }
        }
        super.setRemoved();
    }

    public Optional<GlobalPos> pairedCobbleputer() {
        return Optional.ofNullable(pairedCobbleputer);
    }

    /** Replaces the paired Cobbleputer and updates the loaded-observer index. */
    public void setPairedCobbleputer(GlobalPos target) {
        if (target == null) {
            throw new IllegalArgumentException("paired Cobbleputer must not be null");
        }
        pairedCobbleputer = target;
        setChanged();
        if (level != null && !level.isClientSide()) {
            WirelessObserverNetwork network = CobblewareMod.wirelessObserverNetworkOrNull();
            if (network != null) {
                network.reindex(this);
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (pairedCobbleputer != null) {
            output.putString(PAIRED_DIMENSION_TAG, pairedCobbleputer.dimension().identifier().toString());
            output.putLong(PAIRED_POSITION_TAG, pairedCobbleputer.pos().asLong());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        pairedCobbleputer = readPairing(input).orElse(null);
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
}
