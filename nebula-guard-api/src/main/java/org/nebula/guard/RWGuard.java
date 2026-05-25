package org.nebula.guard;

import org.nebula.core.scheduler.TaskNode;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class RWGuard {
    private static volatile RWGuardConfig config = RWGuardConfig.enabled(RWGuardMode.WARN);
    private static final ThreadLocal<List<RWSetViolation>> LAST_VIOLATIONS =
        ThreadLocal.withInitial(List::of);

    private RWGuard() {
    }

    public static void enable(RWGuardMode newMode) {
        configure(RWGuardConfig.enabled(newMode));
    }

    public static void configure(RWGuardConfig newConfig) {
        config = newConfig;
    }

    public static RWGuardMode mode() {
        return config.mode();
    }

    public static RWGuardConfig config() {
        return config;
    }

    public static List<RWSetViolation> getLastViolations() {
        return LAST_VIOLATIONS.get();
    }

    public static void executeTaskWithTracing(TaskNode task) throws Exception {
        executeTaskWithTracing(0L, task);
    }

    public static void executeTaskWithTracing(long tickNumber, TaskNode task) throws Exception {
        if (!shouldTrace()) {
            LAST_VIOLATIONS.set(List.of());
            task.action().execute();
            return;
        }

        ThreadLocalAccessTrace.reset();
        try {
            task.action().execute();
        } finally {
            ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();
            List<RWSetViolation> violations = capViolations(RWSetConsistencyChecker.check(tickNumber, task, task.declaredRWSet(), actual));
            LAST_VIOLATIONS.set(violations);
            RWGuardReportWriter.appendJsonLines(config.violationLog(), violations);
            if (config.mode() == RWGuardMode.ENFORCE && !violations.isEmpty()) {
                throw new RWGuardViolationException(violations);
            }
        }
    }

    private static boolean shouldTrace() {
        RWGuardConfig current = config;
        if (!current.enabled()) {
            return false;
        }
        return current.samplingRate() >= 1.0 || ThreadLocalRandom.current().nextDouble() < current.samplingRate();
    }

    private static List<RWSetViolation> capViolations(List<RWSetViolation> violations) {
        int max = config.maxViolationsPerTick();
        if (max == 0 || violations.size() <= max) {
            return violations;
        }
        return List.copyOf(violations.subList(0, max));
    }
}
