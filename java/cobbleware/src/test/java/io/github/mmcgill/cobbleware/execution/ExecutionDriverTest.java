package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
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
}
