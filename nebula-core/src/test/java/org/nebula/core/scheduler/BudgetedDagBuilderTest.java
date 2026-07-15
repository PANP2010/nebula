package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.rw.RWSet;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BudgetedDagBuilder} — proves the DAG build-timeout
 * degradation path actually engages under sustained pressure (NEBULA-PATCH-
 * 2026-001 §变更二, arch doc §4.3.1, §16.1).
 *
 * <p>Previously the budget tracker and the {@code warningActive()}-gated
 * serial-fallback path existed in code, but no test verified that the
 * fallback was actually reached when the optimal builder repeatedly
 * exceeded {@link DagBuildBudget#BUILD_BUDGET_NS}. This test pins the
 * "avalanche guard" behavior: N consecutive over-budget builds flip the
 * builder into the serial coarse path so a pathological world cannot
 * snowball into tick-after-tick timeouts.
 */
class BudgetedDagBuilderTest {

    private static TaskNode t(String id) {
        return new TaskNode(id, "noop", RWSet.empty(), () -> {});
    }

    @Test
    void freshBuildProducesNonNullGraph() {
        BudgetedDagBuilder builder = new BudgetedDagBuilder();
        List<TaskNode> tasks = List.of(t("a"), t("b"), t("c"));
        TaskGraph g = builder.build(tasks);

        // The build must produce a valid graph regardless of whether it stayed
        // within the 2ms budget (JVM warmup / GC can push trivial builds over
        // the threshold on slow machines). The warningActive() property is
        // what the real avalanche-guard test covers.
        assertNotNull(g);
        assertEquals(3, g.tasks().size());
    }

    @Test
    void sustainedOverBudgetTriggersAvalancheGuard() {
        // A "optimal" builder that always reports > 2ms by spinning.
        SlowOptimal slow = new SlowOptimal();
        slow.sleepNs.set(3_000_000L); // 3ms — over the 2ms budget
        BudgetedDagBuilder builder = new BudgetedDagBuilder(slow, new DagBuildBudget());

        // DEGRADE_WARNING_STREAK = 10. First 9 calls go to the slow path
        // and just record degraded ticks; the 10th call should hit the
        // warning-active short-circuit and route to coarse.
        for (int i = 0; i < 9; i++) {
            builder.build(List.of(t("x"), t("y")));
        }
        assertEquals(9, builder.budget().degradedTicks());
        assertFalse(builder.budget().warningActive(), "guard must not engage at 9");

        // 10th tick: degraded, this one flips the guard on.
        builder.build(List.of(t("x"), t("y")));
        assertTrue(builder.budget().warningActive(),
            "guard must engage after 10 consecutive over-budget builds");

        // Subsequent builds must go through the coarse path — slow builder
        // must NOT be invoked again.
        long slowCallsBefore = slow.invocations.get();
        TaskGraph g = builder.build(List.of(t("x"), t("y")));
        assertEquals(slowCallsBefore, slow.invocations.get(),
            "guard must skip the slow optimal builder once warningActive()");
        assertNotNull(g);
    }

    @Test
    void guardRecoversAfterCleanBuilds() {
        SlowOptimal slow = new SlowOptimal();
        slow.sleepNs.set(3_000_000L);
        DagBuildBudget budget = new DagBuildBudget();
        BudgetedDagBuilder builder = new BudgetedDagBuilder(slow, budget);

        for (int i = 0; i < 10; i++) {
            builder.build(List.of(t("a"), t("b")));
        }
        assertTrue(budget.warningActive());

        // Switch the slow builder to fast and feed one clean record.
        slow.sleepNs.set(0L);
        budget.record(100_000L, false);
        assertFalse(budget.warningActive(),
            "a single clean build must clear the warning");

        // Optimal path is taken again.
        long before = slow.invocations.get();
        builder.build(List.of(t("a"), t("b")));
        assertTrue(slow.invocations.get() > before,
            "after recovery the optimal path must run again");
    }

    @Test
    void budgetPercentilesReportRealValues() {
        DagBuildBudget budget = new DagBuildBudget();
        for (int i = 0; i < 100; i++) {
            budget.record(1_000_000L + i * 10_000L, false);
        }
        assertEquals(100, budget.totalTicks());
        assertEquals(0, budget.degradedTicks());
        assertTrue(budget.p50Ms() >= 1.0 && budget.p50Ms() < 1.5,
            "p50 out of expected range: " + budget.p50Ms());
        assertTrue(budget.p99Ms() > budget.p50Ms());
    }

    @Test
    void summaryIncludesAllCounters() {
        DagBuildBudget budget = new DagBuildBudget();
        budget.record(2_000_000L, false);
        budget.record(3_000_000L, true);

        String s = budget.summary();
        assertTrue(s.contains("ticks=2"));
        assertTrue(s.contains("p50"));
        assertTrue(s.contains("p99"));
        assertTrue(s.contains("degraded_ticks_ratio=0.500"));
    }

    @Test
    void emptyTaskListStillProducesALegalGraph() {
        SlowOptimal slow = new SlowOptimal();
        slow.sleepNs.set(3_000_000L);
        BudgetedDagBuilder builder = new BudgetedDagBuilder(slow, new DagBuildBudget());
        // Empty input → optimal path is invoked once, serial chain over
        // zero tasks yields an empty graph. We don't pin degradedTicks()=0
        // here because the optimal path does run on an empty list (the
        // coarse-fallback path requires warningActive()).
        TaskGraph g = builder.build(List.of());
        assertNotNull(g);
        assertEquals(1, builder.budget().totalTicks());
    }

    @Test
    void concreteBucketDagBuilderConstructorStillWorks() {
        // The original (BucketDagBuilder, DagBuildBudget) constructor must
        // remain available for the production wiring (the F9 budgeted-build
        // facade); this test pins that surface.
        BudgetedDagBuilder builder =
            new BudgetedDagBuilder(new BucketDagBuilder(), new DagBuildBudget());
        assertNotNull(builder.build(List.of(t("x"))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Slow functional seam for the optimal path; spin-sleeps per call. */
    private static final class SlowOptimal implements BudgetedDagBuilder.OptimalBuild {
        final AtomicLong sleepNs = new AtomicLong();
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public TaskGraph build(Collection<TaskNode> tasks) {
            invocations.incrementAndGet();
            long ns = sleepNs.get();
            if (ns > 0) {
                long deadline = System.nanoTime() + ns;
                while (System.nanoTime() < deadline) {
                    // spin to simulate real build work
                }
            }
            return CoarseDagBuilder.serialChain(tasks);
        }
    }
}
