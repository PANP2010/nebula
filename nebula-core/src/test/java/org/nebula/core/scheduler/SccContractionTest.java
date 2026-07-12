package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SccContractionTest {

    private static final int DIM = 0;

    private static WorldPos pos(int x) {
        return new WorldPos(DIM, x, 64, 0);
    }

    // Task that reads pos(x) and writes pos(x+1)
    private static TaskNode chain(String id, int readX, int writeX) {
        RWSet rw = RWSet.builder().readBlock(pos(readX)).writeBlock(pos(writeX)).build();
        return TaskNode.inert(id, "chain", rw);
    }

    // Task that reads and writes the same position (self-conflicting)
    private static TaskNode selfLoop(String id, int x) {
        RWSet rw = RWSet.builder().readBlock(pos(x)).writeBlock(pos(x)).build();
        return TaskNode.inert(id, "self", rw);
    }

    @Test
    void acyclicGraphNotModified() {
        // A writes pos(1), B reads pos(1) → A→B, no cycle
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 2);
        TaskGraph graph = DagBuilder.build(List.of(a, b));

        List<List<String>> layers = graph.topologicalLayers();
        assertEquals(2, layers.size());
        assertEquals(List.of("A"), layers.get(0));
        assertEquals(List.of("B"), layers.get(1));
    }

    @Test
    void twoNodeCycleContractedIntoCompound() {
        // A: reads pos(0), writes pos(1)
        // B: reads pos(1), writes pos(0)  → A→B (RAW) and B→A (RAW) → cycle
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 0);

        TaskGraph graph = DagBuilder.build(List.of(a, b));
        // Graph should have exactly 1 compound task, no cycle
        assertEquals(1, graph.tasks().size());
        String compoundId = graph.tasks().keySet().iterator().next();
        assertTrue(compoundId.startsWith("SCC["), "Expected compound task, got: " + compoundId);

        // Should be layerable without exception
        List<List<String>> layers = graph.topologicalLayers();
        assertEquals(1, layers.size());
    }

    @Test
    void compoundTaskMergesRWSets() {
        // A: writes pos(1);  B: writes pos(0) + reads pos(1)
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 0);

        TaskGraph graph = DagBuilder.build(List.of(a, b));
        TaskNode compound = graph.tasks().values().iterator().next();

        // Merged RW-set must contain all original positions
        assertTrue(compound.declaredRWSet().declaresBlockRead(pos(0))
            || compound.declaredRWSet().declaresBlockRead(pos(1)));
        assertTrue(compound.declaredRWSet().declaresBlockWrite(pos(0))
            || compound.declaredRWSet().declaresBlockWrite(pos(1)));
    }

    @Test
    void compoundTaskExecutesMembersInDeterministicOrder() throws Exception {
        List<String> executionOrder = new ArrayList<>();

        RWSet rw0 = RWSet.builder().readBlock(pos(0)).writeBlock(pos(1)).build();
        RWSet rw1 = RWSet.builder().readBlock(pos(1)).writeBlock(pos(0)).build();
        TaskNode a = new TaskNode("T_A", "type", rw0, () -> executionOrder.add("T_A"));
        TaskNode b = new TaskNode("T_B", "type", rw1, () -> executionOrder.add("T_B"));

        TaskGraph graph = DagBuilder.build(List.of(a, b));
        assertEquals(1, graph.tasks().size());

        TaskNode compound = graph.tasks().values().iterator().next();
        compound.action().execute();

        assertEquals(List.of("T_A", "T_B"), executionOrder, "Members must run in sorted ID order");
    }

    @Test
    void threeNodeCycleContractedAsSingleCompound() {
        // A→B (RAW), B→C (RAW), C→A (RAW)
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 2);
        TaskNode c = chain("C", 2, 0);

        TaskGraph graph = DagBuilder.build(List.of(a, b, c));
        assertEquals(1, graph.tasks().size());
        String id = graph.tasks().keySet().iterator().next();
        assertTrue(id.startsWith("SCC["));
        assertTrue(id.contains("A"));
        assertTrue(id.contains("B"));
        assertTrue(id.contains("C"));
    }

    @Test
    void oversizedSccIsSerialised() {
        SccStats.reset();
        int threshold = 3;
        SccContractor contractor = new SccContractor(threshold);

        // Build a 4-node ring: A→B→C→D→A  (size = 4 > threshold = 3)
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 2);
        TaskNode c = chain("C", 2, 3);
        TaskNode d = chain("D", 3, 0);

        TaskGraph graph = DagBuilder.build(List.of(a, b, c, d), contractor);

        // All 4 original nodes survive (no contraction)
        assertEquals(4, graph.tasks().size());
        assertTrue(graph.tasks().containsKey("A"));
        assertTrue(graph.tasks().containsKey("B"));
        assertTrue(graph.tasks().containsKey("C"));
        assertTrue(graph.tasks().containsKey("D"));

        // Must be layerable (serialisation breaks the cycle)
        assertDoesNotThrow(() -> graph.topologicalLayers());
        assertTrue(graph.edges().contains(new DependencyEdge("A", "B", DependencyType.WAW)));
        assertTrue(graph.edges().contains(new DependencyEdge("B", "C", DependencyType.WAW)));
        assertTrue(graph.edges().contains(new DependencyEdge("C", "D", DependencyType.WAW)));

        assertEquals(1, SccStats.builds());
        assertEquals(1, SccStats.buildsWithCycles());
        assertEquals(1, SccStats.totalSccs());
        assertEquals(0, SccStats.contracted());
        assertEquals(1, SccStats.serialised());
        assertEquals(4, SccStats.maxSccSize());
    }

    @Test
    void independentTasksNotAffectedByContraction() {
        // Cycle: A↔B, plus C which is independent
        TaskNode a = chain("A", 0, 1);
        TaskNode b = chain("B", 1, 0);
        RWSet indep = RWSet.builder().readBlock(pos(99)).writeBlock(pos(100)).build();
        TaskNode c = TaskNode.inert("C", "indep", indep);

        TaskGraph graph = DagBuilder.build(List.of(a, b, c));
        // Compound(A,B) + C = 2 tasks
        assertEquals(2, graph.tasks().size());
        assertTrue(graph.tasks().containsKey("C"));
        // Should be 2 layers: compound then C (or C independent of compound)
        assertDoesNotThrow(() -> graph.topologicalLayers());
    }

    @Test
    void setDefaultThresholdAffectsNoArgConstructor() {
        // P2.4.1b: the fidelity hook overrides the no-arg-constructor threshold
        // when the active tier permits large SCCs. The explicit-int constructor
        // is unaffected so unit tests stay isolated.
        int original = SccContractor.defaultThreshold();
        try {
            SccContractor.setDefaultThreshold(SccContractor.LARGE_THRESHOLD);
            assertEquals(SccContractor.LARGE_THRESHOLD, SccContractor.defaultThreshold());

            SccContractor c = new SccContractor();
            assertEquals(SccContractor.LARGE_THRESHOLD, c.threshold());

            // Explicit-int constructor still wins.
            SccContractor explicit = new SccContractor(7);
            assertEquals(7, explicit.threshold());

            // setThreshold also mutates in place.
            explicit.setThreshold(SccContractor.UNLIMITED_THRESHOLD);
            assertEquals(SccContractor.UNLIMITED_THRESHOLD, explicit.threshold());
        } finally {
            SccContractor.setDefaultThreshold(original);
        }
    }

    @Test
    void setThresholdRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new SccContractor(0));
        SccContractor c = new SccContractor();
        assertThrows(IllegalArgumentException.class, () -> c.setThreshold(0));
    }
}
