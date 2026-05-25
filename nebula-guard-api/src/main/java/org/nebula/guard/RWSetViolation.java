package org.nebula.guard;

import org.nebula.core.rw.RWSet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RWSetViolation(
    String violationId,
    Instant timestamp,
    long tickNumber,
    String taskId,
    String taskType,
    ViolationType violationType,
    AccessTarget accessTarget,
    RWSet declaredRWSet,
    List<StackTraceElement> stackTrace,
    String suggestedFix
) {
    public static RWSetViolation create(
        long tickNumber,
        String taskId,
        String taskType,
        ViolationType violationType,
        AccessTarget accessTarget,
        RWSet declaredRWSet,
        String suggestedFix
    ) {
        return new RWSetViolation(
            "RW-VIOL-" + UUID.randomUUID(),
            Instant.now(),
            tickNumber,
            taskId,
            taskType,
            violationType,
            accessTarget,
            declaredRWSet,
            List.of(Thread.currentThread().getStackTrace()),
            suggestedFix
        );
    }

    public static RWSetViolation reconstruct(
        String violationId,
        Instant timestamp,
        long tickNumber,
        String taskId,
        String taskType,
        ViolationType violationType,
        AccessTarget accessTarget,
        RWSet declaredRWSet,
        String suggestedFix
    ) {
        return new RWSetViolation(
            violationId,
            timestamp,
            tickNumber,
            taskId,
            taskType,
            violationType,
            accessTarget,
            declaredRWSet,
            List.of(),
            suggestedFix
        );
    }
}
