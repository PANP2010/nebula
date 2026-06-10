package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the live block-entity subsystem (hopper transfer + furnace smelting) on
 * the versioned {@link BlockEntityState}, modelling MC 1.21.4 behaviour.
 */
class BlockEntitySubsystemTest {

    private static final int DIM = 0;

    private static int slot(BlockEntityState s, WorldPos p, int i) {
        return s.get(new BlockEntityField(p, "inventory.slots[" + i + "]"));
    }

    private static void putSlot(BlockEntityState s, WorldPos p, int i, int n) {
        s.put(new BlockEntityField(p, "inventory.slots[" + i + "]"), n);
    }

    /** Runs one block-entity tick of the given tasks through the runner. */
    private static void runTick(BlockEntityState state, Map<String, BlockEntityAction> actions,
                                List<TaskNode> tasks) throws Exception {
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(state, actions::get);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String id : layer) {
                TaskNode t = graph.tasks().get(id);
                if (t != null) runner.run(t);
            }
            runner.commitLayer();
            runner.resetLayer();
        }
    }

    // ── Hopper ────────────────────────────────────────────────────────────────

    @Test
    void hopperPullsAndPushesOneItemThenCoolsDown() throws Exception {
        WorldPos self = new WorldPos(DIM, 0, 64, 0);
        WorldPos above = new WorldPos(DIM, 0, 65, 0);
        WorldPos output = new WorldPos(DIM, 0, 63, 0);
        BlockEntityState state = new BlockEntityState();
        putSlot(state, above, 0, 10);   // source has 10 items

        BlockEntitySnapshotState snap = new BlockEntitySnapshotState();
        BlockEntityActions.hopper(self, above, output, 5)
            .execute(new BlockEntityContext(state, snap));
        assertTrue(snap.commit(state).success());

        // Pulled one from above; pushed one of those onward to output.
        assertEquals(9, slot(state, above, 0), "one item pulled from above");
        assertEquals(1, slot(state, output, 0), "one item pushed to output");
        assertEquals(BlockEntityActions.HOPPER_COOLDOWN,
            state.get(new BlockEntityField(self, "transfer_cooldown")),
            "transfer arms the 8-tick cooldown");
    }

    @Test
    void hopperRespectsCooldown() throws Exception {
        WorldPos self = new WorldPos(DIM, 0, 64, 0);
        WorldPos above = new WorldPos(DIM, 0, 65, 0);
        WorldPos output = new WorldPos(DIM, 0, 63, 0);
        BlockEntityState state = new BlockEntityState();
        putSlot(state, above, 0, 10);
        state.put(new BlockEntityField(self, "transfer_cooldown"), 5); // on cooldown

        BlockEntitySnapshotState snap = new BlockEntitySnapshotState();
        BlockEntityActions.hopper(self, above, output, 5)
            .execute(new BlockEntityContext(state, snap));
        assertTrue(snap.commit(state).success());

        assertEquals(10, slot(state, above, 0), "no transfer while on cooldown");
        assertEquals(4, state.get(new BlockEntityField(self, "transfer_cooldown")),
            "cooldown decrements");
    }

    // ── Furnace ─────────────────────────────────────────────────────────────

    @Test
    void furnaceSmeltsOneItemAfterCookTotalTicks() throws Exception {
        WorldPos self = new WorldPos(DIM, 0, 64, 0);
        BlockEntityState state = new BlockEntityState();
        putSlot(state, self, 0, 3);   // 3 input items
        putSlot(state, self, 1, 1);   // 1 fuel item (200 burn ticks)
        putSlot(state, self, 2, 0);   // empty output

        BlockEntityAction furnace = BlockEntityActions.furnace(self);

        // Tick exactly COOK_TOTAL times: one item should be smelted.
        for (int i = 0; i < BlockEntityActions.COOK_TOTAL; i++) {
            BlockEntitySnapshotState snap = new BlockEntitySnapshotState();
            furnace.execute(new BlockEntityContext(state, snap));
            assertTrue(snap.commit(state).success());
        }

        assertEquals(2, slot(state, self, 0), "one input consumed");
        assertEquals(1, slot(state, self, 2), "one output produced");
        assertEquals(0, slot(state, self, 1), "fuel item was consumed to start burning");
        assertEquals(0, state.get(new BlockEntityField(self, "cook_progress")),
            "cook progress resets after a smelt");
    }

    @Test
    void furnaceDoesNothingWithoutFuel() throws Exception {
        WorldPos self = new WorldPos(DIM, 0, 64, 0);
        BlockEntityState state = new BlockEntityState();
        putSlot(state, self, 0, 3);   // input but...
        putSlot(state, self, 1, 0);   // no fuel

        BlockEntityAction furnace = BlockEntityActions.furnace(self);
        for (int i = 0; i < BlockEntityActions.COOK_TOTAL; i++) {
            BlockEntitySnapshotState snap = new BlockEntitySnapshotState();
            furnace.execute(new BlockEntityContext(state, snap));
            snap.commit(state);
        }
        assertEquals(3, slot(state, self, 0), "no smelting without fuel");
        assertEquals(0, slot(state, self, 2));
    }

    // ── Determinism through the runner ────────────────────────────────────────

    @Test
    void blockEntityTickIsDeterministic() throws Exception {
        assertEquals(simulate(), simulate(),
            "block-entity simulation must be reproducible");
    }

    private int simulate() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();

        // A row of 4 furnaces, each with input + fuel.
        for (int i = 0; i < 4; i++) {
            WorldPos self = new WorldPos(DIM, i * 4, 64, 0); // spaced so they don't conflict
            putSlot(state, self, 0, 5);
            putSlot(state, self, 1, 2);
            BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(self);
            TaskNode t = BlockEntityTaskFactory.furnaceInert(snap);
            actions.put(t.taskId(), BlockEntityActions.furnace(self));
            tasks.add(t);
        }

        for (int tick = 0; tick < 250; tick++) {
            runTick(state, actions, tasks);
        }

        // Sum of output across all furnaces — a deterministic scalar.
        int total = 0;
        for (int i = 0; i < 4; i++) {
            total += slot(state, new WorldPos(DIM, i * 4, 64, 0), 2);
        }
        assertTrue(total > 0, "furnaces should have smelted something in 250 ticks");
        return total;
    }
}
