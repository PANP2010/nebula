package org.nebula.plugin;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.entity.ExplosionTaskGuardHook;
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

/** Live per-task RW guard for the small explosion block-destroy path (B8 C4). */
public final class ExplosionRwGuardHook implements ExplosionTaskGuardHook {

    private static final Logger LOG = Logger.getLogger(ExplosionRwGuardHook.class.getName());

    private final RWGuardConfig config;
    private final AtomicLong tracedTasks = new AtomicLong();
    private final AtomicLong violationCount = new AtomicLong();
    private final ThreadLocal<Boolean> sampledCurrent = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public ExplosionRwGuardHook(RWGuardConfig config) {
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
        LOG.warning(() -> "RW-GUARD violation: explosion task " + task.taskId()
            + " performed " + violations.size() + " undeclared access(es) — see "
            + config.violationLog());
    }

    public long tracedTasks() {
        return tracedTasks.get();
    }

    public long violationCount() {
        return violationCount.get();
    }
}
