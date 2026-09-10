package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import io.github.mmcgill.cobbleware.CobblewareMod;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;

/** Allocates the vanilla client states used to render every observer color and orientation. */
public final class WirelessObserverPolymerModels {
    private static volatile Map<DyeColor, Map<Direction, BlockState>> states;

    private WirelessObserverPolymerModels() {
    }

    public static synchronized void initialize() {
        if (states != null) {
            return;
        }

        Map<DyeColor, Map<Direction, BlockState>> allocatedColors = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            Map<Direction, BlockState> allocatedFacings = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.values()) {
                Rotation rotation = Rotation.forFacing(facing);
                BlockState allocatedState = PolymerBlockResourceUtils.requestBlock(
                        BlockModelType.FULL_BLOCK,
                        PolymerBlockModel.of(modelIdFor(color), rotation.x(), rotation.y(), true));
                if (allocatedState == null) {
                    throw new IllegalStateException(
                            "Polymer could not allocate a FULL_BLOCK model for Wireless Observer "
                                    + color.getName() + " facing " + facing.getSerializedName());
                }
                allocatedFacings.put(facing, allocatedState);
            }
            allocatedColors.put(color, Map.copyOf(allocatedFacings));
        }
        states = Map.copyOf(allocatedColors);
    }

    public static BlockState stateFor(DyeColor color, Direction facing) {
        Map<DyeColor, Map<Direction, BlockState>> initializedStates = states;
        if (initializedStates == null) {
            throw new IllegalStateException("Wireless Observer Polymer models have not been initialized");
        }
        return initializedStates.get(color).get(facing);
    }

    static Identifier modelIdFor(DyeColor color) {
        return Identifier.fromNamespaceAndPath(
                CobblewareMod.MOD_ID, "block/" + color.getName() + "_wireless_observer");
    }

    private record Rotation(int x, int y) {
        private static Rotation forFacing(Direction facing) {
            return switch (facing) {
                case NORTH -> new Rotation(0, 0);
                case EAST -> new Rotation(0, 90);
                case SOUTH -> new Rotation(0, 180);
                case WEST -> new Rotation(0, 270);
                case UP -> new Rotation(90, 0);
                case DOWN -> new Rotation(270, 0);
            };
        }
    }
}
