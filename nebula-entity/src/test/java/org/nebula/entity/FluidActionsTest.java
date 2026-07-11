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
    void waterSourceFlowsTowardPassableNeighbourWithLowestLevel() throws Exception {
        FluidSnapshot source = FluidSnapshot.water(SELF, 0, true);
        // South neighbour already holds a lower-level passable (same fluid) flow than the
        // others, so vanilla slope selection routes the new water south.
        FluidSnapshot southLow = FluidSnapshot.water(source.south(), 2, false);
        FluidSnapshot eastHigh = FluidSnapshot.water(source.east(), 5, false);
        FluidState state = initialState(source);
        state.put(source.down(), new Object());
        state.put(source.south(), southLow);
        state.put(source.east(), eastHigh);

        run(source, state);

        assertEquals(FluidSnapshot.water(source.south(), 1, false), state.get(source.south()));
        assertEquals(eastHigh, state.get(source.east()));
        assertNull(state.get(source.north()));
        assertNull(state.get(source.west()));
    }

    @Test
    void solidNeighbourBlocksFlowEvenWhenOtherNeighboursArePassable() throws Exception {
        FluidSnapshot source = FluidSnapshot.water(SELF, 0, true);
        FluidSnapshot eastLow = FluidSnapshot.water(source.east(), 4, false);
        FluidState state = initialState(source);
        Object northSolid = new Object();
        Object downSolid = new Object();
        state.put(source.down(), downSolid);
        state.put(source.north(), northSolid); // solid on the north face
        state.put(source.east(), eastLow);

        run(source, state);

        // North is solid (not passable). East already holds same-fluid at level 4; slope selection
        // picks the lowest non-zero same-fluid neighbour, so east is the candidate. Vanilla writes
        // the new flow level (1) into east, overwriting the level-4 cell. South and west are
        // empty; not selected because slope selection chose east as lowest same-fluid neighbour.
        assertEquals(FluidSnapshot.water(source.east(), 1, false), state.get(source.east()));
        assertEquals(northSolid, state.get(source.north()));
        assertEquals(downSolid, state.get(source.down()));
        assertNull(state.get(source.south()));
        assertNull(state.get(source.west()));
    }

    @Test
    void waterFlowIncrementsItsHorizontalLevel() throws Exception {
        FluidSnapshot flow = FluidSnapshot.water(SELF, 4, false);
        FluidState state = runWithBlockedDown(flow);

        // Slope selection picks the first viable (lowest-level) neighbour; with no pre-existing
        // fluids to disambiguate, this resolves to north (the fixed-order tiebreak).
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
    void differentFluidNeighbourDoesNotCountAsLowestLevelFluid() throws Exception {
        FluidSnapshot water = FluidSnapshot.water(SELF, 0, true);
        FluidSnapshot lavaNeighbour = FluidSnapshot.lava(water.east(), 1, false);
        FluidState state = initialState(water);
        state.put(water.down(), new Object());
        state.put(water.east(), lavaNeighbour);

        run(water, state);

        // East holds a different fluid (lava); slope selection treats it as non-passable for water,
        // so east is not the chosen direction. North is the first passable air (null) cell in
        // [N, S, E, W] order, so the algorithm flows north.
        assertEquals(lavaNeighbour, state.get(water.east()));
        assertEquals(FluidSnapshot.water(water.north(), 1, false), state.get(water.north()));
        assertNull(state.get(water.south()));
        assertNull(state.get(water.west()));
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
