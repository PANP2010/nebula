package org.nebula.plugin;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.entity.BlockEntityTaskGuardHook;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardReportWriter;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The live RW-guard bridge for the block-entity DAG (B8 C3 — the definition-of-done
 * slice that turns C3's now-correct declared RW-sets into guard-VERIFIED ones). The
 * block-entity analogue of {@link RedstoneRwGuardHook}.
 *
 * <p>Installed on the live {@link org.nebula.entity.BlockEntityTaskRunner} only when
 * {@code -Dnebula.rw.guard=true}. It brackets every dispatched block-entity task:
 * <ol>
 *   <li>{@link #beforeTask} resets {@link ThreadLocalAccessTrace} so the trace holds
 *       only this task's accesses (fed by {@link BlockEntityRwGuardTracer} on the task
 *       context).</li>
 *   <li>{@link #afterTask} snapshots the trace and runs {@link RWSetConsistencyChecker}
 *       against the task's declared {@code RWSet}; any violation is a real undeclared
 *       field access — appended to the configured violation log.</li>
 * </ol>
 *
 * <p><b>Scope of this live check:</b> block-entity field reads/writes (slots + timers)
 * are routed through {@code BlockEntityContext} today, so the guard verifies exactly the
 * field accesses the hopper/furnace/… actions perform. A compound (SCC-contracted ring
 * of hoppers) is bracketed once with its merged RW-set, and the traced accesses are the
 * union of its members' — so union-vs-merge is a sound "zero violations" check.
 *
 * <p>Sampling: {@link RWGuardConfig#samplingRate()} decides per-task whether to trace,
 * so a low rate keeps the hot path cheap. A non-sampled task still runs normally; its
 * trace is simply not checked. Counters ({@link #tracedTasks}, {@link #violationCount})
 * are cumulative so {@code executeOwnedBlockEntityDag} can log a one-line summary the
 * operator can read off {@code server-run.log}.
 */
public final class BlockEntityRwGuardHook implements BlockEntityTaskGuardHook {

    private static final Logger LOG = Logger.getLogger(BlockEntityRwGuardHook.class.getName());

    private final RWGuardConfig config;
    private final AtomicLong tracedTasks = new AtomicLong();
    private final AtomicLong violationCount = new AtomicLong();

    /** Per-thread flag: was the current task sampled (and thus should be checked)? */
    private final ThreadLocal<Boolean> sampledCurrent = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public BlockEntityRwGuardHook(RWGuardConfig config) {
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
        // log even without reading the JSONL file. The full detail (field path, stack,
        // declared set, suggested fix) is in the JSONL line.
        LOG.warning(() -> "RW-GUARD violation: block-entity task " + task.taskId()
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
