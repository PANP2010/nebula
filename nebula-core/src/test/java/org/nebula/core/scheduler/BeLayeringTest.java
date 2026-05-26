package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the layering strategy that BlockEntityTaskBuilder produces
 * when fed through the DAG scheduler.
 *
 * <p>Three BE categories exist:
 * - INDEPENDENT (furnace/chest/etc): self-only RWSet → should land in same layer
 * - HOPPER: self + facing + above → should serialize across layers if chained
 * - CONSERVATIVE (spawner/beacon/etc): GLOBAL_RW → serializes with other GLOBAL_RW
 *   tasks, but does NOT conflict with positional tasks (global + positional key
 *   spaces are orthogonal by design — this is correct: a spawner's global state
 *   does not interfere with a furnace at a different position)
 */
class BeLayeringTest {

    // Capture which DAG layer each task ran in by recording the batch index.
    private record LayerSnapshot(Map<String, Integer> taskToLayer) {}

    private static LayerSnapshot runWithLayerTracking(List<TaskNode> tasks) throws Exception {
        Map<String, Integer> taskToLayer = new HashMap<>();
        final int[] batchIndex = {0};

        TaskRunner trackingRunner = new TaskRunner() {
            @Override
            public void run(TaskNode task) {
                taskToLayer.put(task.taskId(), batchIndex[0]);
            }

            @Override
            public void runLayer(List<TaskNode> layer) {
                for (TaskNode task : layer) {
                    taskToLayer.put(task.taskId(), batchIndex[0]);
                }
                batchIndex[0]++;
            }
        };

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), trackingRunner);
        pipeline.execute(tasks);
        return new LayerSnapshot(taskToLayer);
    }

    private static WorldPos wp(int dim, int x, int y, int z) {
        return new WorldPos(dim, x, y, z);
    }

    private static TaskNode independentBE(String id, int dim, int x, int y, int z) {
        WorldPos self = wp(dim, x, y, z);
        RWSet rw = RWSet.builder()
            .writeBlockEntity(new BlockEntityField(self, "*"))
            .readBlock(self)
            .build();
        return TaskNode.parallelSafe(id, "BLOCK_ENTITY", rw, () -> {});
    }

    private static TaskNode hopperBE(String id, int dim, int x, int y, int z,
                                     int facingX, int facingY, int facingZ) {
        WorldPos self = wp(dim, x, y, z);
        WorldPos facing = wp(dim, facingX, facingY, facingZ);
        WorldPos above = wp(dim, x, y + 1, z);
        RWSet rw = RWSet.builder()
            .writeBlockEntity(new BlockEntityField(self, "*"))
            .writeBlockEntity(new BlockEntityField(facing, "*"))
            .readBlockEntity(new BlockEntityField(above, "*"))
            .readBlock(self)
            .readBlock(facing)
            .readBlock(above)
            .build();
        return new TaskNode(id, "BLOCK_ENTITY_HOPPER", rw, () -> {});
    }

    private static TaskNode globalBE(String id) {
        RWSet rw = RWSet.builder()
            .readGlobal(GlobalKey.ALL)
            .writeGlobal(GlobalKey.ALL)
            .build();
        return new TaskNode(id, "BLOCK_ENTITY_GLOBAL", rw, () -> {});
    }

    @Test
    void independentBEsShareSameLayer() throws Exception {
        // Three furnaces at different positions — all self-only, no conflicts.
        List<TaskNode> tasks = List.of(
            independentBE("furnace_0", 0, 100, 64, 100),
            independentBE("furnace_1", 0, 100, 64, 101),
            independentBE("furnace_2", 0, 100, 64, 102)
        );

        LayerSnapshot snap = runWithLayerTracking(tasks);

        // All three should be in layer 0 — same value = same layer.
        assertEquals(snap.taskToLayer.get("furnace_0"), snap.taskToLayer.get("furnace_1"));
        assertEquals(snap.taskToLayer.get("furnace_1"), snap.taskToLayer.get("furnace_2"));
        assertEquals(0, snap.taskToLayer.get("furnace_0"));
    }

    @Test
    void independentBEsDoNotConflictWithEachOther() throws Exception {
        // Two furnaces have no overlapping WorldPos — DAG builder should
        // put them in the same layer (parallel-eligible).
        List<TaskNode> tasks = List.of(
            independentBE("a", 0, 10, 64, 10),
            independentBE("b", 0, 20, 64, 20)
        );

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(tasks);

        // With TaskRunner.DIRECT, layersExecuted counts topological layers.
        // Non-conflicting tasks should be in 1 layer.
        assertEquals(1, result.layersExecuted());
    }

    @Test
    void hopperChainSerializesAcrossLayers() throws Exception {
        // Three hoppers in a chain: A → B → C (each writes the next as facing).
        // A writes self + B; B writes self + C; C writes self + "exit".
        // A and B conflict (B's pos), B and C conflict (C's pos).
        TaskNode a = hopperBE("hopper_A", 0, 0, 64, 0, 1, 64, 0);
        TaskNode b = hopperBE("hopper_B", 0, 1, 64, 0, 2, 64, 0);
        TaskNode c = hopperBE("hopper_C", 0, 2, 64, 0, 3, 64, 0);

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(a, b, c));

        // Hopper chain: A→B conflict, B→C conflict, so minimum 2 layers.
        assertTrue(result.layersExecuted() >= 2,
            "Hopper chain should be serialized across at least 2 layers, got " + result.layersExecuted());
    }

    @Test
    void twoGlobalRWTasksAreSCCContracted() throws Exception {
        // Two GLOBAL_RW tasks: both declare writtenGlobalKeys={ALL}.
        // GLOBAL.ALL.conflictsWith(ALL) = true → WAW A→B + WAW B→A + RAW A→B + RAW B→A
        // = cycle → SccContractor merges them into one compound task → 1 layer.
        // This is CORRECT: they ARE serialized (compound task runs sequentially),
        // just not via separate DAG layers.
        TaskNode spawner1 = globalBE("spawner_0");
        TaskNode spawner2 = globalBE("spawner_1");

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(spawner1, spawner2));

        // SCC contraction → 1 compound task → 1 layer executed.
        assertEquals(1, result.layersExecuted(),
            "Two GLOBAL_RW tasks should be SCC-contracted into 1 compound task");
        // SCC-contracted compound replaces both sub-tasks in completedTaskIds.
        // The compound's ID is deterministic from the SCC — at least 1 entry.
        assertTrue(result.completedTaskIds().size() >= 1,
            "SCC compound task present in completedTaskIds");
    }

    @Test
    void sccContractedGlobalDoesNotLeakIntoPositionalLayer() throws Exception {
        // GLOBAL_RW pair (SCC-contracted) alongside positional tasks.
        // The contracted compound retains GLOBAL_RW → still no positional conflict
        // → 1 layer for the compound + 1 layer for positional = 1 total layer.
        TaskNode spawner1 = globalBE("sp0");
        TaskNode spawner2 = globalBE("sp1");
        TaskNode furnace = independentBE("f0", 0, 50, 64, 50);

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(spawner1, spawner2, furnace));

        // GLOBAL pair contracts to 1 compound; furnace has no conflict with
        // GLOBAL keys → all in 1 layer.
        assertEquals(1, result.layersExecuted(),
            "SCC-contracted GLOBAL pair + positional task should share 1 layer (orthogonal key spaces)");
        // 2 tasks: the SCC compound + the furnace.
        assertTrue(result.completedTaskIds().size() >= 2,
            "Expected at least 2 tasks (SCC compound + furnace)");
    }

    @Test
    void globalRWDoesNotConflictWithPositionalTasks() throws Exception {
        // GLOBAL_RW (global key space) vs self-only BE (positional key space):
        // key spaces are orthogonal → no conflict → same layer.
        TaskNode spawner = globalBE("spawner_0");
        TaskNode independent = independentBE("furnace_0", 0, 50, 64, 50);

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(spawner, independent));

        // GLOBAL_RW only has global keys; independentBE only has positional keys.
        // These key spaces don't overlap → no conflict → 1 layer.
        assertEquals(1, result.layersExecuted(),
            "GLOBAL_RW and positional tasks should share a layer (orthogonal key spaces)");
    }

    @Test
    void mixedBETasksLayerCorrectly() throws Exception {
        // Realistic mix: 3 independent furnaces + 1 hopper + 1 GLOBAL spawner.
        List<TaskNode> tasks = List.of(
            independentBE("f0", 0, 10, 64, 10),
            independentBE("f1", 0, 10, 64, 11),
            independentBE("f2", 0, 10, 64, 12),
            hopperBE("h0", 0, 20, 64, 20, 21, 64, 20),
            globalBE("sp0")
        );

        TickPipeline pipeline = new TickPipeline(completed -> List.of(), TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(tasks);

        // Hopper touches positional keys → conflicts with nothing here (its
        // facing position 21,64,20 doesn't overlap with furnaces at 10,64,10-12
        // or the global spawner).
        // All 5 tasks should be in 1 layer.
        assertEquals(1, result.layersExecuted(),
            "Mixed BE types with non-overlapping positions should share 1 layer");

        // All tasks completed.
        assertEquals(5, result.completedTaskIds().size());
    }
}
