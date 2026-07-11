package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidTaskFactoryTest {

    private static final WorldPos WATER_POS = new WorldPos(0, 50, 64, 50);
    private static final WorldPos LAVA_POS = new WorldPos(-1, 30, 40, 30);

    @Test
    void waterFlowTaskId() {
        FluidSnapshot snap = FluidSnapshot.water(WATER_POS, 7, true);
        TaskNode node = FluidTaskFactory.flowInert(snap);
        assertEquals("FLUID_WATER_FLOW@0:50,64,50", node.taskId());
        assertEquals("FLUID_WATER_FLOW", node.taskType());
    }

    @Test
    void lavaFlowTaskId() {
        FluidSnapshot snap = FluidSnapshot.lava(LAVA_POS, 3, true);
        TaskNode node = FluidTaskFactory.flowInert(snap);
        assertEquals("FLUID_LAVA_FLOW@-1:30,40,30", node.taskId());
    }

    @Test
    void flowReadsNeighboursAndWritesFlowPositions() {
        FluidSnapshot snap = FluidSnapshot.water(WATER_POS, 5, false);
        TaskNode node = FluidTaskFactory.flowInert(snap);
        RWSet rw = node.declaredRWSet();

        // Reads self + 5 neighbours
        assertTrue(rw.declaresBlockRead(WATER_POS));
        assertTrue(rw.declaresBlockRead(new WorldPos(0, 50, 63, 50))); // down
        assertTrue(rw.declaresBlockRead(new WorldPos(0, 50, 64, 49))); // north
        assertTrue(rw.declaresBlockRead(new WorldPos(0, 50, 64, 51))); // south
        assertTrue(rw.declaresBlockRead(new WorldPos(0, 51, 64, 50))); // east
        assertTrue(rw.declaresBlockRead(new WorldPos(0, 49, 64, 50))); // west

        // Writes every possible flow-to position, but does not rewrite unchanged self state
        assertFalse(rw.declaresBlockWrite(WATER_POS));
        assertTrue(rw.declaresBlockWrite(new WorldPos(0, 50, 63, 50))); // down
        assertTrue(rw.declaresBlockWrite(new WorldPos(0, 50, 64, 49))); // north

        assertTrue(rw.writtenEvents().contains(EventType.BLOCK_UPDATE));
    }

    @Test
    void removeOnlyWritesSelf() {
        FluidSnapshot snap = FluidSnapshot.water(WATER_POS, 3, false);
        TaskNode node = FluidTaskFactory.removeInert(snap);
        RWSet rw = node.declaredRWSet();

        assertEquals("FLUID_REMOVE", node.taskType());
        assertTrue(rw.declaresBlockRead(WATER_POS));
        assertTrue(rw.declaresBlockWrite(WATER_POS));
        assertTrue(rw.writtenEvents().contains(EventType.BLOCK_UPDATE));
        // Should not write neighbours
        assertFalse(rw.declaresBlockWrite(new WorldPos(0, 50, 63, 50)));
    }

    @Test
    void adjacentWaterFlowsConflict() {
        WorldPos pos1 = new WorldPos(0, 10, 64, 10);
        WorldPos pos2 = new WorldPos(0, 11, 64, 10); // east of pos1

        FluidSnapshot flow1 = FluidSnapshot.water(pos1, 6, false);
        FluidSnapshot flow2 = FluidSnapshot.water(pos2, 5, false);

        TaskNode node1 = FluidTaskFactory.flowInert(flow1);
        TaskNode node2 = FluidTaskFactory.flowInert(flow2);

        // flow1 writes east (pos2), flow2 reads self (pos2) → RAW conflict
        assertTrue(node1.declaredRWSet().hasReadWriteConflictWith(node2.declaredRWSet())
            || node1.declaredRWSet().hasWriteWriteConflictWith(node2.declaredRWSet()),
            "adjacent fluid flows must conflict — they share positions in RW sets");
    }

    @Test
    void distantFluidsDoNotConflict() {
        FluidSnapshot a = FluidSnapshot.water(new WorldPos(0, 0, 64, 0), 7, true);
        FluidSnapshot b = FluidSnapshot.water(new WorldPos(0, 100, 64, 100), 7, true);

        TaskNode nodeA = FluidTaskFactory.flowInert(a);
        TaskNode nodeB = FluidTaskFactory.flowInert(b);

        assertFalse(nodeA.declaredRWSet().hasReadWriteConflictWith(nodeB.declaredRWSet()));
        assertFalse(nodeA.declaredRWSet().hasWriteWriteConflictWith(nodeB.declaredRWSet()));
    }

    @Test
    void fluidTaskTypeRoundtrip() {
        assertEquals(FluidTaskType.WATER_FLOW, FluidTaskType.fromTaskType("FLUID_WATER_FLOW"));
        assertEquals(FluidTaskType.LAVA_FLOW, FluidTaskType.fromTaskType("FLUID_LAVA_FLOW"));
        assertEquals(FluidTaskType.FLUID_REMOVE, FluidTaskType.fromTaskType("FLUID_REMOVE"));
        assertNull(FluidTaskType.fromTaskType("UNKNOWN"));
    }

    @Test
    void flowReadsEveryNeighbourBlockTypeForPassability() {
        FluidSnapshot snap = FluidSnapshot.water(WATER_POS, 5, false);
        TaskNode node = FluidTaskFactory.flowInert(snap);
        RWSet rw = node.declaredRWSet();

        // Six-block footprint — including all four horizontal neighbours, whose block types the
        // slope-selection pass must inspect to decide passability.
        WorldPos north = new WorldPos(0, 50, 64, 49);
        WorldPos south = new WorldPos(0, 50, 64, 51);
        WorldPos east = new WorldPos(0, 51, 64, 50);
        WorldPos west = new WorldPos(0, 49, 64, 50);
        WorldPos down = new WorldPos(0, 50, 63, 50);

        assertTrue(rw.declaresBlockRead(WATER_POS));
        assertTrue(rw.declaresBlockRead(down));
        assertTrue(rw.declaresBlockRead(north));
        assertTrue(rw.declaresBlockRead(south));
        assertTrue(rw.declaresBlockRead(east));
        assertTrue(rw.declaresBlockRead(west));
    }

    @Test
    void flowActionPicksLowestLevelPassableNeighbourAsSingleFlowDirection() throws Exception {
        FluidSnapshot self = FluidSnapshot.water(WATER_POS, 0, true);
        FluidState state = new FluidState();
        state.put(WATER_POS, self);
        state.put(self.down(), new Object());
        state.put(self.north(), FluidSnapshot.water(self.north(), 4, false));
        state.put(self.south(), FluidSnapshot.water(self.south(), 1, false));
        state.put(self.east(), FluidSnapshot.water(self.east(), 3, false));
        // West stays null (air, passable-but-zero-level).

        TaskNode task = FluidTaskFactory.flowInert(self);
        FluidTaskRunner runner = new FluidTaskRunner(state,
            id -> FluidActions.flow(self), null);
        runner.run(task);
        assertTrue(runner.commit(task.taskId()));

        // South wins slope selection (lowest non-zero level). The other three neighbours must
        // be untouched — vanilla slope selection picks exactly one direction per tick.
        assertEquals(FluidSnapshot.water(self.south(), 1, false), state.get(self.south()));
        assertEquals(FluidSnapshot.water(self.north(), 4, false), state.get(self.north()));
        assertEquals(FluidSnapshot.water(self.east(), 3, false), state.get(self.east()));
        assertNull(state.get(self.west()));
    }
}
