package io.github.mmcgill.cobbleware.item;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** Persists the Cobbleputer location paired to an individual Cobblemeter stack. */
public final class CobblemeterPairing {
    static final String DIMENSION_KEY = "cobbleware:paired_dimension";
    static final String POSITION_KEY = "cobbleware:paired_pos";

    private CobblemeterPairing() {}

    /** Returns the paired Cobbleputer location, or empty when the stack is unpaired or malformed. */
    public static Optional<GlobalPos> getPairedCobbleputer(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return Optional.empty();
        }
        return getPairedCobbleputer(customData.copyTag());
    }

    static Optional<GlobalPos> getPairedCobbleputer(CompoundTag tag) {
        Optional<String> dimensionName = tag.getString(DIMENSION_KEY);
        Optional<Long> packedPosition = tag.getLong(POSITION_KEY);
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

    /** Replaces the Cobblemeter stack's pairing while preserving unrelated custom item data. */
    public static void pair(ItemStack stack, GlobalPos target) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> pair(tag, target));
    }

    static void pair(CompoundTag tag, GlobalPos target) {
        tag.putString(DIMENSION_KEY, target.dimension().identifier().toString());
        tag.putLong(POSITION_KEY, target.pos().asLong());
    }
}
