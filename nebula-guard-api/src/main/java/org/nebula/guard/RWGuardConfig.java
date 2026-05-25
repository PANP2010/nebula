package org.nebula.guard;

import java.nio.file.Path;
import java.util.Objects;

public record RWGuardConfig(
    boolean enabled,
    double samplingRate,
    RWGuardMode mode,
    Path violationLog,
    int maxViolationsPerTick,
    boolean autoPatch
) {
    public RWGuardConfig {
        Objects.requireNonNull(mode, "mode");
        if (samplingRate < 0.0 || samplingRate > 1.0) {
            throw new IllegalArgumentException("samplingRate must be between 0.0 and 1.0");
        }
        if (maxViolationsPerTick < 0) {
            throw new IllegalArgumentException("maxViolationsPerTick must be non-negative");
        }
        if (autoPatch && mode != RWGuardMode.TEST) {
            throw new IllegalArgumentException("autoPatch is only valid in TEST mode");
        }
    }

    public static RWGuardConfig defaults() {
        return new RWGuardConfig(false, 0.01, RWGuardMode.WARN, Path.of("logs", "nebula-rw-violations.log"), 100, false);
    }

    public static RWGuardConfig enabled(RWGuardMode mode) {
        return new RWGuardConfig(true, 1.0, mode, null, 100, mode == RWGuardMode.TEST);
    }

    public RWGuardConfig withViolationLog(Path path) {
        return new RWGuardConfig(enabled, samplingRate, mode, path, maxViolationsPerTick, autoPatch);
    }
}
