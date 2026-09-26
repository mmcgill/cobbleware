package io.github.mmcgill.cobbleware.block;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import io.github.mmcgill.cobbleware.CobblewareMod;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Allocates and maps the vanilla client states used to render Cobbleputers through Polymer. */
public final class CobbleputerPolymerModels {
    private static final Map<CobbleputerState, Identifier> MODEL_IDS = Map.of(
            CobbleputerState.UNINITIALIZED, id("cobbleputer_uninitialized"),
            CobbleputerState.RUNNING, id("cobbleputer_running"),
            CobbleputerState.STOPPED, id("cobbleputer_stopped"),
            CobbleputerState.CRASHED, id("cobbleputer_crashed"));

    private static volatile Map<CobbleputerState, BlockState> states;

    private CobbleputerPolymerModels() {}

    /** Allocates one Polymer full-block model state per visible Cobbleputer lifecycle state. */
    public static synchronized void initialize() {
        if (states != null) {
            return;
        }

        Map<CobbleputerState, BlockState> allocatedStates = new EnumMap<>(CobbleputerState.class);
        for (CobbleputerState lifecycleState : CobbleputerState.values()) {
            BlockState allocatedState = PolymerBlockResourceUtils.requestBlock(
                    BlockModelType.FULL_BLOCK, PolymerBlockModel.of(modelIdFor(lifecycleState)));
            if (allocatedState == null) {
                throw new IllegalStateException(
                        "Polymer could not allocate a FULL_BLOCK model for Cobbleputer state " + lifecycleState);
            }
            allocatedStates.put(lifecycleState, allocatedState);
        }
        states = Map.copyOf(allocatedStates);
    }

    /** Returns the vanilla state Polymer must send for the supplied real Cobbleputer state. */
    public static BlockState stateFor(CobbleputerState lifecycleState) {
        Map<CobbleputerState, BlockState> initializedStates = states;
        if (initializedStates == null) {
            throw new IllegalStateException("Cobbleputer Polymer models have not been initialized");
        }
        return initializedStates.get(lifecycleState);
    }

    static Identifier modelIdFor(CobbleputerState lifecycleState) {
        return MODEL_IDS.get(lifecycleState);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(CobblewareMod.MOD_ID, "block/" + path);
    }
}
