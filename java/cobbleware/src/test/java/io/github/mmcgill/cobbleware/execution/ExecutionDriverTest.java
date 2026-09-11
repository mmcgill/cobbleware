package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExecutionDriverTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blockInfoContainsTheRegistryIdentifierAndAllStateProperties() {
        BlockState state = Blocks.OAK_DOOR.defaultBlockState();

        cobbleware.events.BlockInfo blockInfo = ExecutionDriver.protoBlockInfo(state);

        assertEquals("minecraft", blockInfo.getBlockType().getNs());
        assertEquals("oak_door", blockInfo.getBlockType().getPath());
        assertEquals("north", blockInfo.getPropertiesOrThrow("facing"));
        assertEquals("lower", blockInfo.getPropertiesOrThrow("half"));
        assertEquals("false", blockInfo.getPropertiesOrThrow("open"));
        assertEquals(state.getProperties().size(), blockInfo.getPropertiesCount());
    }

    @Test
    void mapsEverySpecifiedProtoDyeColorAndRejectsInvalidSelectors() {
        Map<cobbleware.common.DyeColor, DyeColor> expected = Map.ofEntries(
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_WHITE, DyeColor.WHITE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_ORANGE, DyeColor.ORANGE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_MAGENTA, DyeColor.MAGENTA),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIGHT_BLUE, DyeColor.LIGHT_BLUE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_YELLOW, DyeColor.YELLOW),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIME, DyeColor.LIME),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_PINK, DyeColor.PINK),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_GRAY, DyeColor.GRAY),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIGHT_GRAY, DyeColor.LIGHT_GRAY),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_CYAN, DyeColor.CYAN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_PURPLE, DyeColor.PURPLE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BLUE, DyeColor.BLUE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BROWN, DyeColor.BROWN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_GREEN, DyeColor.GREEN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_RED, DyeColor.RED),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BLACK, DyeColor.BLACK));

        for (Map.Entry<cobbleware.common.DyeColor, DyeColor> entry : expected.entrySet()) {
            assertEquals(Optional.of(entry.getValue()), ExecutionDriver.minecraftDyeColor(entry.getKey()));
        }
        assertEquals(
                Optional.empty(),
                ExecutionDriver.minecraftDyeColor(cobbleware.common.DyeColor.DYE_COLOR_UNSPECIFIED));
        assertEquals(Optional.empty(), ExecutionDriver.minecraftDyeColor(cobbleware.common.DyeColor.UNRECOGNIZED));
    }
}
