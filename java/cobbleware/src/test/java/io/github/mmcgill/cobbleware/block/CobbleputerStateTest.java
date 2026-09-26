package io.github.mmcgill.cobbleware.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class CobbleputerStateTest {
    @Test
    void usesStableLowercaseNamesForBlockStateSerialization() {
        assertEquals("uninitialized", CobbleputerState.UNINITIALIZED.getSerializedName());
        assertEquals("running", CobbleputerState.RUNNING.getSerializedName());
        assertEquals("stopped", CobbleputerState.STOPPED.getSerializedName());
        assertEquals("crashed", CobbleputerState.CRASHED.getSerializedName());
    }

    @Test
    void mapsEveryLifecycleStateToItsDedicatedPolymerModel() {
        assertEquals(
                id("cobbleputer_uninitialized"), CobbleputerPolymerModels.modelIdFor(CobbleputerState.UNINITIALIZED));
        assertEquals(id("cobbleputer_running"), CobbleputerPolymerModels.modelIdFor(CobbleputerState.RUNNING));
        assertEquals(id("cobbleputer_stopped"), CobbleputerPolymerModels.modelIdFor(CobbleputerState.STOPPED));
        assertEquals(id("cobbleputer_crashed"), CobbleputerPolymerModels.modelIdFor(CobbleputerState.CRASHED));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("cobbleware", "block/" + path);
    }
}
