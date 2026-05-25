package org.nebula.core.bucket;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagExecutor;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BucketDagBuilderTest {

    private static final int DIM = 0;

    private static WorldPos pos(int x) {
        return new WorldPos(DIM, x, 64, 0);
    }

    private static TaskNode task(String id, int readX, int writeX) {
        RWSet rw = RWSet.builder().readBlock(pos(readX)).writeBlock(pos(writeX)).build();
        return TaskNode.inert(id, "test", rw);
    }

    @Test
    void emptyInputProducesEmptyGraph() {
        TaskGraph graph = new BucketDagBuilder().build(List.of());
        assertTrue(graph.tasks().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void singleTaskNoConflict() {
        TaskGraph graph = new BucketDagBuilder().build(List.of(task("A", 0, 1)));
        assertEquals(1, graph.tasks().size());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void independentTasksInSameBucketHaveNoEdges() {
        // Both tasks in bucket(0,0,0): A reads pos(0) writes pos(1), B reads pos(5) writes pos(6)
        // No conflict (disjoint positions within same 32-chunk bucket)
        TaskNode a = task("A", 0, 1);
        TaskNode b = task("B", 5, 6);
        TaskGraph graph = new BucketDagBuilder().build(List.of(a, b));
        assertEquals(2, graph.tasks().size());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void conflictingTasksInSameBucketGetEdge() {
        // A writes pos(1), B reads pos(1) → RAW dependency
        TaskNode a = task("A", 0, 1);
        TaskNode b = task("B", 1, 2);
        TaskGraph graph = new BucketDagBuilder().build(List.of(a, b));
        assertEquals(2, graph.tasks().size());
        assertFalse(graph.edges().isEmpty());
        List<List<String>> layers = graph.topologicalLayers();
        assertEquals(2, layers.size());
        assertEquals(List.of("A"), layers.get(0));
        assertEquals(List.of("B"), layers.get(1));
    }

    @Test
    void tasksInDifferentBucketsNoConflictAreIndependent() {
        // Bucket 0: pos(0) .. pos(31*16-1)
        // Bucket 1: pos(32*16) onward  (32 chunks * 16 blocks/chunk = 512 block units)
        // Use chunk coords: bucket 0 = x∈[0,31], bucket 1 = x∈[32,63]
        int bucket0X = 5;
        int bucket1X = 40;  // floor(40/32)=1, different bucket
        TaskNode a = task("A", bucket0X, bucket0X + 1);
        TaskNode b = task("B", bucket1X, bucket1X + 1);
        TaskGraph graph = new BucketDagBuilder().build(List.of(a, b));
        // No positional overlap → no edges
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void cyclicGraphContractedIntoCompound() {
        // A writes pos(1), B reads pos(1) and writes pos(0), A reads pos(0) → cycle
        TaskNode a = task("A", 0, 1);
        TaskNode b = task("B", 1, 0);
        TaskGraph graph = new BucketDagBuilder().build(List.of(a, b));
        // Should be contracted into 1 compound task
        assertEquals(1, graph.tasks().size());
        assertDoesNotThrow(() -> graph.topologicalLayers());
    }

    @Test
    void largeIndependentSetAllLayeredTogether() {
        // 10 tasks, each touching completely disjoint positions
        List<TaskNode> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(task("T" + i, i * 10, i * 10 + 1));
        }
        TaskGraph graph = new BucketDagBuilder().build(tasks);
        assertEquals(10, graph.tasks().size());
        assertTrue(graph.edges().isEmpty());
        List<List<String>> layers = graph.topologicalLayers();
        assertEquals(1, layers.size());
        assertEquals(10, layers.get(0).size());
    }
}
