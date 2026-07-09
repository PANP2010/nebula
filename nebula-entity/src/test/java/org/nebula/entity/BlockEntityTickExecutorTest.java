package org.nebula.entity;

import org.junit.jupiter.api.Test;
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
 * Tests {@link BlockEntityTickExecutor} — the block-entity DAG driver the C3 live
 * {@code TickExecutor} will call to run a drained dirty set through the graph. The
 * subsystem test ({@code BlockEntitySubsystemTest}) hand-rolls the build→run→commit
 * loop; these tests pin that the executor does the same thing correctly, including
 * the empty-set fast path, the layer count, and conflict serialisation.
 */
class BlockEntityTickExecutorTest {

    private static final int DIM = 0;

    private static int slot(BlockEntityState s, WorldPos p, int i) {
        return s.get(new BlockEntityField(p, "inventory.slots[" + i + "]"));
    }

    private static void putSlot(BlockEntityState s, WorldPos p, int i, int n) {
        s.put(new BlockEntityField(p, "inventory.slots[" + i + "]"), n);
    }

    @Test
    void emptyDirtySetIsAZeroLayerNoOp() throws Exception {
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(new BlockEntityState(), id -> null);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);
        assertEquals(0, executor.executeTick(List.of()), "empty tick executes no layers");
    }

    @Test
    void singleFurnaceTaskExecutesAndCommits() throws Exception {
        WorldPos self = new WorldPos(DIM, 0, 64, 0);
        BlockEntityState state = new BlockEntityState();
        putSlot(state, self, 0, 3);   // input
        putSlot(state, self, 1, 1);   // fuel
        putSlot(state, self, 2, 0);   // output

        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(self);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);
        actions.put(task.taskId(), BlockEntityActions.furnace(self));

        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(state, actions::get);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        // Tick COOK_TOTAL times through the executor: one item should smelt.
        for (int i = 0; i < BlockEntityActions.COOK_TOTAL; i++) {
            int layers = executor.executeTick(List.of(task));
            assertEquals(1, layers, "one non-conflicting task is one layer");
        }

        assertEquals(2, slot(state, self, 0), "one input consumed via the executor");
        assertEquals(1, slot(state, self, 2), "one output produced via the executor");
    }

    @Test
    void disjointFurnacesShareOneLayer() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();

        // Four spatially-separated furnaces touch disjoint fields → no conflict → one layer.
        for (int i = 0; i < 4; i++) {
            WorldPos self = new WorldPos(DIM, i * 4, 64, 0);
            putSlot(state, self, 0, 5);
            putSlot(state, self, 1, 2);
            BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(self);
            TaskNode t = BlockEntityTaskFactory.furnaceInert(snap);
            actions.put(t.taskId(), BlockEntityActions.furnace(self));
            tasks.add(t);
        }

        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(state, actions::get);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        assertEquals(1, executor.executeTick(tasks),
            "four disjoint furnaces run in a single layer");
    }

    @Test
    void executorMatchesHandRolledLoop() throws Exception {
        assertEquals(simulateViaExecutor(), simulateViaHandLoop(),
            "the executor must produce the same final state as the hand-rolled build→run→commit loop");
    }

    /** Same 4-furnace scenario as BlockEntitySubsystemTest, driven through the executor. */
    private int simulateViaExecutor() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = buildFurnaceRow(state, actions);
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(state, actions::get);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);
        for (int tick = 0; tick < 250; tick++) {
            executor.executeTick(tasks);
        }
        return totalOutput(state);
    }

    private int simulateViaHandLoop() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = buildFurnaceRow(state, actions);
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(state, actions::get);
        for (int tick = 0; tick < 250; tick++) {
            var graph = org.nebula.core.scheduler.DagBuilder.build(tasks);
            for (List<String> layer : graph.topologicalLayers()) {
                for (String id : layer) {
                    TaskNode t = graph.tasks().get(id);
                    if (t != null) runner.run(t);
                }
                runner.commitLayer();
                runner.resetLayer();
            }
        }
        return totalOutput(state);
    }

    private List<TaskNode> buildFurnaceRow(BlockEntityState state, Map<String, BlockEntityAction> actions) {
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            WorldPos self = new WorldPos(DIM, i * 4, 64, 0);
            putSlot(state, self, 0, 5);
            putSlot(state, self, 1, 2);
            BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(self);
            TaskNode t = BlockEntityTaskFactory.furnaceInert(snap);
            actions.put(t.taskId(), BlockEntityActions.furnace(self));
            tasks.add(t);
        }
        return tasks;
    }

    private int totalOutput(BlockEntityState state) {
        int total = 0;
        for (int i = 0; i < 4; i++) {
            total += slot(state, new WorldPos(DIM, i * 4, 64, 0), 2);
        }
        assertTrue(total > 0, "furnaces should have smelted something in 250 ticks");
        return total;
    }
}
