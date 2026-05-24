package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntityTaskGeneratorTest {

    @Test
    void hopperChainGeneratesDownstreamHopper() {
        WorldPos posA = new WorldPos(0, 10, 60, 10);
        WorldPos posB = new WorldPos(0, 11, 60, 10);
        WorldPos posC = new WorldPos(0, 12, 60, 10);

        BlockEntitySnapshot hopperA = BlockEntitySnapshot.hopper(posA, 1, 0, 0); // faces east -> posB
        BlockEntitySnapshot hopperB = BlockEntitySnapshot.hopper(posB, 1, 0, 0); // faces east -> posC
        BlockEntitySnapshot hopperC = BlockEntitySnapshot.hopper(posC, 1, 0, 0); // faces east -> beyond

        Map<WorldPos, BlockEntitySnapshot> map = Map.of(posA, hopperA, posB, hopperB, posC, hopperC);
        BlockEntityTaskGenerator gen = new BlockEntityTaskGenerator(map);

        TaskNode taskA = BlockEntityTaskFactory.hopperInert(hopperA);
        List<TaskNode> downstream = gen.generateFrom(taskA);

        assertEquals(1, downstream.size());
        assertEquals("BLOCK_ENTITY_HOPPER@0:11,60,10", downstream.get(0).taskId());
    }

    @Test
    void hopperWithNoDownstreamHopperGeneratesNothing() {
        WorldPos posA = new WorldPos(0, 10, 60, 10);
        BlockEntitySnapshot hopperA = BlockEntitySnapshot.hopper(posA, 1, 0, 0);

        Map<WorldPos, BlockEntitySnapshot> map = Map.of(posA, hopperA);
        BlockEntityTaskGenerator gen = new BlockEntityTaskGenerator(map);

        TaskNode taskA = BlockEntityTaskFactory.hopperInert(hopperA);
        List<TaskNode> downstream = gen.generateFrom(taskA);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void furnaceDoesNotPropagate() {
        WorldPos pos = new WorldPos(0, 50, 60, 50);
        BlockEntitySnapshot furnace = BlockEntitySnapshot.furnace(pos);

        Map<WorldPos, BlockEntitySnapshot> map = Map.of(pos, furnace);
        BlockEntityTaskGenerator gen = new BlockEntityTaskGenerator(map);

        TaskNode task = BlockEntityTaskFactory.furnaceInert(furnace);
        List<TaskNode> downstream = gen.generateFrom(task);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void hopperOutputIntoFurnaceDoesNotPropagate() {
        WorldPos posA = new WorldPos(0, 10, 60, 10);
        WorldPos posB = new WorldPos(0, 11, 60, 10);

        BlockEntitySnapshot hopper = BlockEntitySnapshot.hopper(posA, 1, 0, 0);
        BlockEntitySnapshot furnace = BlockEntitySnapshot.furnace(posB);

        Map<WorldPos, BlockEntitySnapshot> map = Map.of(posA, hopper, posB, furnace);
        BlockEntityTaskGenerator gen = new BlockEntityTaskGenerator(map);

        TaskNode task = BlockEntityTaskFactory.hopperInert(hopper);
        List<TaskNode> downstream = gen.generateFrom(task);

        assertTrue(downstream.isEmpty(), "hopper into furnace should not trigger downstream hopper propagation");
    }

    @Test
    void parseTaskIdPos() {
        WorldPos result = BlockEntityTaskGenerator.parseTaskIdPos("BLOCK_ENTITY_HOPPER@0:100,50,200");
        assertNotNull(result);
        assertEquals(new WorldPos(0, 100, 50, 200), result);
    }

    @Test
    void parseTaskIdPosInvalid() {
        assertNull(BlockEntityTaskGenerator.parseTaskIdPos("garbage"));
        assertNull(BlockEntityTaskGenerator.parseTaskIdPos("TYPE@nocoords"));
    }
}
