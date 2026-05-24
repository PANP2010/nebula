package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidTaskGeneratorTest {

    @Test
    void waterPropagatesDownAndHorizontal() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        WorldPos down = new WorldPos(0, 10, 63, 10);
        WorldPos south = new WorldPos(0, 10, 64, 11);

        FluidSnapshot self = FluidSnapshot.water(pos, 7, true);
        FluidSnapshot downFluid = FluidSnapshot.water(down, 6, false);
        FluidSnapshot southFluid = FluidSnapshot.water(south, 5, false);

        Map<WorldPos, FluidSnapshot> map = Map.of(pos, self, down, downFluid, south, southFluid);
        FluidTaskGenerator gen = new FluidTaskGenerator(map);

        TaskNode task = FluidTaskFactory.flowInert(self);
        List<TaskNode> downstream = gen.generateFrom(task);

        assertEquals(2, downstream.size());
        assertTrue(downstream.stream().anyMatch(t -> t.taskId().contains("10,63,10")));
        assertTrue(downstream.stream().anyMatch(t -> t.taskId().contains("10,64,11")));
    }

    @Test
    void fluidAtEdgeDoesNotPropagateToEmpty() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        FluidSnapshot self = FluidSnapshot.water(pos, 7, true);

        // Only self in the map — no neighbours
        Map<WorldPos, FluidSnapshot> map = Map.of(pos, self);
        FluidTaskGenerator gen = new FluidTaskGenerator(map);

        TaskNode task = FluidTaskFactory.flowInert(self);
        List<TaskNode> downstream = gen.generateFrom(task);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void fluidRemoveDoesNotPropagate() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        WorldPos down = new WorldPos(0, 10, 63, 10);

        FluidSnapshot self = FluidSnapshot.water(pos, 3, false);
        FluidSnapshot downFluid = FluidSnapshot.water(down, 2, false);

        Map<WorldPos, FluidSnapshot> map = Map.of(pos, self, down, downFluid);
        FluidTaskGenerator gen = new FluidTaskGenerator(map);

        TaskNode removeTask = FluidTaskFactory.removeInert(self);
        List<TaskNode> downstream = gen.generateFrom(removeTask);

        assertTrue(downstream.isEmpty(), "FLUID_REMOVE should not propagate");
    }

    @Test
    void parsePos() {
        WorldPos result = FluidTaskGenerator.parsePos("FLUID_WATER_FLOW@0:50,64,50");
        assertNotNull(result);
        assertEquals(new WorldPos(0, 50, 64, 50), result);
    }
}
