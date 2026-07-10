package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidActionsTest {

    private static final WorldPos SELF = new WorldPos(0, 10, 64, 10);

    @Test
    void downwardFlowWinsAndUsesFallingLevel() throws Exception {
        FluidSnapshot source = FluidSnapshot.water(SELF, 0, true);
        FluidState state = run(source);

        assertEquals(FluidSnapshot.water(source.down(), 8, false), state.get(source.down()));
        assertNull(state.get(source.north()));
        assertNull(state.get(source.south()));
        assertNull(state.get(source.east()));
        assertNull(state.get(source.west()));
    }

    @Test
    void waterSourceSpreadsLevelOneToEveryEmptyHorizontalNeighbour() throws Exception {
        FluidSnapshot source = FluidSnapshot.water(SELF, 0, true);
        FluidState state = runWithBlockedDown(source);

        assertEquals(FluidSnapshot.water(source.north(), 1, false), state.get(source.north()));
        assertEquals(FluidSnapshot.water(source.south(), 1, false), state.get(source.south()));
        assertEquals(FluidSnapshot.water(source.east(), 1, false), state.get(source.east()));
        assertEquals(FluidSnapshot.water(source.west(), 1, false), state.get(source.west()));
    }

    @Test
    void waterFlowIncrementsItsHorizontalLevel() throws Exception {
        FluidSnapshot flow = FluidSnapshot.water(SELF, 4, false);
        FluidState state = runWithBlockedDown(flow);

        assertEquals(FluidSnapshot.water(flow.north(), 5, false), state.get(flow.north()));
    }

    @Test
    void overworldLavaDropsTwoLevelsHorizontally() throws Exception {
        FluidSnapshot lava = FluidSnapshot.lava(SELF, 3, false);
        FluidState state = runWithBlockedDown(lava);

        assertEquals(FluidSnapshot.lava(lava.north(), 5, false), state.get(lava.north()));
    }

    @Test
    void netherLavaDropsOneLevelHorizontally() throws Exception {
        WorldPos netherPos = new WorldPos(-1, 10, 64, 10);
        FluidSnapshot lava = FluidSnapshot.lava(netherPos, 3, false);
        FluidState state = runWithBlockedDown(lava);

        assertEquals(FluidSnapshot.lava(lava.north(), 4, false), state.get(lava.north()));
    }

    @Test
    void exhaustedHorizontalLevelDoesNotSpread() throws Exception {
        FluidSnapshot water = FluidSnapshot.water(SELF, 7, false);
        FluidState state = runWithBlockedDown(water);

        assertNull(state.get(water.north()));
        assertNull(state.get(water.south()));
        assertNull(state.get(water.east()));
        assertNull(state.get(water.west()));
    }

    @Test
    void occupiedHorizontalNeighbourIsNotOverwritten() throws Exception {
        FluidSnapshot source = FluidSnapshot.water(SELF, 0, true);
        FluidSnapshot occupied = FluidSnapshot.water(source.north(), 4, false);
        FluidState state = initialState(source);
        state.put(source.down(), new Object());
        state.put(source.north(), occupied);

        run(source, state);

        assertEquals(occupied, state.get(source.north()));
        assertEquals(FluidSnapshot.water(source.south(), 1, false), state.get(source.south()));
    }

    private static FluidState run(FluidSnapshot fluid) throws Exception {
        FluidState state = initialState(fluid);
        run(fluid, state);
        return state;
    }

    private static FluidState runWithBlockedDown(FluidSnapshot fluid) throws Exception {
        FluidState state = initialState(fluid);
        state.put(fluid.down(), new Object());
        run(fluid, state);
        return state;
    }

    private static FluidState initialState(FluidSnapshot fluid) {
        FluidState state = new FluidState();
        state.put(fluid.pos(), fluid);
        return state;
    }

    private static void run(FluidSnapshot fluid, FluidState state) throws Exception {
        TaskNode task = FluidTaskFactory.flowInert(fluid);
        FluidTaskRunner runner = new FluidTaskRunner(state, ignored -> FluidActions.flow(fluid), null);
        runner.run(task);
        assertTrue(runner.commit(task.taskId()));
    }
}
