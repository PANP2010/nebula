package org.nebula.plugin;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardReportWriter;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.redstone.RedstoneTaskGuardHook;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The live RW-guard bridge for the redstone DAG (B8 task B2b — patch-001's
 * Achilles'-heel protection running against real Folia for the first time).
 *
 * <p>Installed on the live {@link org.nebula.redstone.RedstoneTaskRunner} only when
 * {@code -Dnebula.rw.guard=true}. It brackets every dispatched redstone task:
 * <ol>
 *   <li>{@link #beforeTask} resets {@link ThreadLocalAccessTrace} so the trace holds
 *       only this task's accesses (fed by {@link RedstoneRwGuardTracer} on the
 *       task context).</li>
 *   <li>{@link #afterTask} snapshots the trace and runs {@link RWSetConsistencyChecker}
 *       against the task's declared {@code RWSet}; any violation is a real
 *       undeclared access — appended to the configured violation log.</li>
 * </ol>
 *
 * <p><b>Scope of this live check:</b> only block-level reads/writes are routed
 * through {@code RedstoneTaskContext} today, so the guard verifies exactly the
 * block accesses the redstone actions perform. Block-entity / entity / global /
 * random accesses are not yet traced live (see {@link RedstoneRwGuardTracer}).
 *
 * <p>Sampling: {@link RWGuardConfig#samplingRate()} decides per-task whether to
 * trace, so a low rate keeps the hot path cheap. A non-sampled task still runs
 * normally; its trace is simply not checked. Counters ({@link #tracedTasks},
 * {@link #violationCount}) are cumulative so {@code executeOwnedDag} can log a
 * one-line summary the operator can read off {@code server-run.log}.
 */
public final class RedstoneRwGuardHook implements RedstoneTaskGuardHook {

    private static final Logger LOG = Logger.getLogger(RedstoneRwGuardHook.class.getName());

    private final RWGuardConfig config;
    private final AtomicLong tracedTasks = new AtomicLong();
    private final AtomicLong violationCount = new AtomicLong();

    /** Per-thread flag: was the current task sampled (and thus should be checked)? */
    private final ThreadLocal<Boolean> sampledCurrent = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public RedstoneRwGuardHook(RWGuardConfig config) {
        this.config = config;
    }

    @Override
    public void beforeTask(TaskNode task) {
        boolean sample = config.enabled()
            && (config.samplingRate() >= 1.0
                || ThreadLocalRandom.current().nextDouble() < config.samplingRate());
        sampledCurrent.set(sample);
        if (sample) {
            ThreadLocalAccessTrace.reset();
        }
    }

    @Override
    public void afterTask(TaskNode task) {
        if (!sampledCurrent.get()) {
            return;
        }
        sampledCurrent.set(Boolean.FALSE);
        tracedTasks.incrementAndGet();

        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();
        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);
        if (violations.isEmpty()) {
            return;
        }

        violationCount.addAndGet(violations.size());
        RWGuardReportWriter.appendJsonLines(config.violationLog(), violations);
        // One WARNING per violating task so a real drift is impossible to miss in the
        // log even without reading the JSONL file. The full detail (coords, stack,
        // declared set, suggested fix) is in the JSONL line.
        LOG.warning(() -> "RW-GUARD violation: task " + task.taskId()
            + " performed " + violations.size() + " undeclared access(es) — see "
            + config.violationLog());
    }

    /** Cumulative count of tasks whose trace was sampled and checked. */
    public long tracedTasks() {
        return tracedTasks.get();
    }

    /** Cumulative count of RW-set violations found across all checked tasks. */
    public long violationCount() {
        return violationCount.get();
    }
}
