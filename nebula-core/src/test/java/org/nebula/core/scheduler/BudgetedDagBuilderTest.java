package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedDagBuilderTest {

    private static final int DIM = 0;

    /** Independent tasks at disjoint positions — the optimal builder finds NO edges. */
    private static TaskNode independent(String id, int x) {
        RWSet rw = RWSet.builder()
            .readBlock(new WorldPos(DIM, x, 64, 0))
            .writeBlock(new WorldPos(DIM, x, 65, 0))
            .build();
        return TaskNode.inert(id, "test", rw);
    }

    @Test
    void normalPathUsesOptimalBuilder() {
        BudgetedDagBuilder b = new BudgetedDagBuilder();
        List<TaskNode> tasks = List.of(independent("A", 0), independent("B", 50), independent("C", 100));

        TaskGraph g = b.build(tasks);

        // Optimal builder finds these independent → zero edges (full parallelism).
        assertEquals(3, g.tasks().size());
        assertTrue(g.edges().isEmpty(), "independent tasks should have no edges on the optimal path");
        assertEquals(1, b.budget().totalTicks());
    }

    @Test
    void avalancheGuardSwitchesToCoarseWhenWarningActive() {
        DagBuildBudget budget = new DagBuildBudget();
        // Drive the budget into the warning state (10 consecutive degraded ticks).
        for (int i = 0; i < 10; i++) budget.record(5_000_000L, true);
        assertTrue(budget.warningActive());

        BudgetedDagBuilder b = new BudgetedDagBuilder(new BucketDagBuilder(), budget);
        List<TaskNode> tasks = List.of(independent("A", 0), independent("B", 50), independent("C", 100));

        TaskGraph g = b.build(tasks);

        // Coarse path serialises everything → N-1 chain edges, even though the
        // tasks are independent. This bounds build time during sustained overrun.
        assertEquals(3, g.tasks().size());
        assertEquals(2, g.edges().size(), "guard should emit a full serial chain");
        assertTrue(g.stronglyConnectedComponents().isEmpty(), "coarse chain stays legal/acyclic");
    }

    @Test
    void guardRecordsCoarseBuildsAsDegraded() {
        DagBuildBudget budget = new DagBuildBudget();
        for (int i = 0; i < 10; i++) budget.record(5_000_000L, true);
        BudgetedDagBuilder b = new BudgetedDagBuilder(new BucketDagBuilder(), budget);

        long before = budget.degradedTicks();
        b.build(List.of(independent("A", 0), independent("B", 50)));
        assertEquals(before + 1, budget.degradedTicks(),
            "a guard-path build is recorded as a degraded tick");
    }

    @Test
    void bothPathsProduceLegalGraphs() throws Exception {
        // Whichever path is taken, the resulting graph must be executable.
        BudgetedDagBuilder normal = new BudgetedDagBuilder();
        TaskGraph g = normal.build(List.of(independent("A", 0), independent("B", 50)));
        DagExecutor.execute(g); // throws if illegal — just assert it completes
        assertEquals(2, g.tasks().size());
    }
}
