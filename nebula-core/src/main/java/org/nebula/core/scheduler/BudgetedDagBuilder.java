package org.nebula.core.scheduler;

import org.nebula.core.bucket.BucketDagBuilder;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Budget-aware DAG builder facade (arch doc §4.3.1, §16.1;
 * NEBULA-PATCH-2026-001 §变更二).
 *
 * <p>Wraps the optimal {@link BucketDagBuilder} with the build-time budget and
 * an avalanche guard. Each tick:
 * <ol>
 *   <li>If the previous ticks degraded persistently (the budget tracker's
 *       consecutive-degrade streak is at the warning threshold), proactively
 *       use the fast {@link CoarseDagBuilder} — it cannot time out, so it stops
 *       a slow structure from causing tick-after-tick build overruns.</li>
 *   <li>Otherwise run the optimal builder, time it, and if it exceeded the
 *       total build budget, record the tick as degraded so the guard engages if
 *       the overrun persists.</li>
 * </ol>
 *
 * <p>Either path yields a legal DAG, so correctness is never at risk — the
 * guard only trades parallelism for bounded build time under sustained load.
 *
 * <p>Note: the patch also describes interrupting an in-flight parallel build at
 * 1.5ms and coarsening only the unfinished buckets. That requires an
 * interruptible parallel build (the current {@code BucketDagBuilder} joins its
 * fork-join tasks and cannot be pre-empted without risking nondeterminism), so
 * it is deferred. This facade implements the coarser, deterministic guard:
 * detect sustained overruns and switch the whole build to the serial fallback.
 */
public final class BudgetedDagBuilder {

    /**
     * Functional seam for the optimal-build path. The default implementation
     * delegates to a {@link BucketDagBuilder}; tests inject a slow/stub
     * supplier to drive the budget tracker into the degraded path.
     */
    @FunctionalInterface
    public interface OptimalBuild {
        TaskGraph build(Collection<TaskNode> tasks);
    }

    private final OptimalBuild optimal;
    private final DagBuildBudget budget;

    public BudgetedDagBuilder(BucketDagBuilder optimal, DagBuildBudget budget) {
        this(optimal::build, budget);
    }

    public BudgetedDagBuilder(OptimalBuild optimal, DagBuildBudget budget) {
        this.optimal = Objects.requireNonNull(optimal, "optimal");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public BudgetedDagBuilder() {
        this(new BucketDagBuilder(), new DagBuildBudget());
    }

    public DagBuildBudget budget() {
        return budget;
    }

    /**
     * Builds the DAG for {@code tasks}, honouring the build-time budget.
     *
     * @return a legal {@link TaskGraph} (optimal when within budget, coarse
     *         serial chain when the avalanche guard is engaged)
     */
    public TaskGraph build(Collection<TaskNode> tasks) {
        // Avalanche guard: if recent ticks degraded persistently, skip the
        // optimal build entirely and emit the fast coarse DAG. This bounds
        // build time deterministically while the dense structure persists.
        if (budget.warningActive()) {
            long t0 = System.nanoTime();
            TaskGraph coarse = CoarseDagBuilder.serialChain(tasks);
            budget.record(System.nanoTime() - t0, true);
            return coarse;
        }

        long t0 = System.nanoTime();
        TaskGraph graph = optimal.build(tasks);
        long elapsed = System.nanoTime() - t0;

        boolean degraded = budget.isOverBudget(elapsed);
        budget.record(elapsed, degraded);
        return graph;
    }
}
