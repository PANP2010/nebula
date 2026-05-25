package org.nebula.guard;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

public final class RWSetConsistencyChecker {
    private RWSetConsistencyChecker() {
    }

    public static List<RWSetViolation> check(TaskNode task, ActualAccessTrace actual) {
        return check(0L, task, task.declaredRWSet(), actual);
    }

    public static List<RWSetViolation> check(long tickNumber, TaskNode task, RWSet declared, ActualAccessTrace actual) {
        List<RWSetViolation> violations = new ArrayList<>();

        for (WorldPos pos : actual.readBlocks()) {
            if (!declared.declaresBlockRead(pos)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_READ, AccessTarget.block(pos),
                    "Add block read to " + task.taskType() + ": " + pos));
            }
        }

        for (WorldPos pos : actual.writtenBlocks()) {
            if (!declared.declaresBlockWrite(pos)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_WRITE, AccessTarget.block(pos),
                    "Add block write to " + task.taskType() + ": " + pos));
            }
        }

        for (BlockEntityField field : actual.readBlockEntities()) {
            if (!declared.declaresBlockEntityRead(field)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_READ, AccessTarget.blockEntityField(field),
                    "Add block entity read to " + task.taskType() + ": " + field));
            }
        }

        for (BlockEntityField field : actual.writtenBlockEntities()) {
            if (!declared.declaresBlockEntityWrite(field)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_WRITE, AccessTarget.blockEntityField(field),
                    "Add block entity write to " + task.taskType() + ": " + field));
            }
        }

        for (EntityField field : actual.readEntityFields()) {
            if (!declared.declaresEntityRead(field)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_READ, AccessTarget.entityField(field),
                    "Add entity read to " + task.taskType() + ": " + field));
            }
        }

        for (EntityField field : actual.writtenEntityFields()) {
            if (!declared.declaresEntityWrite(field)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_WRITE, AccessTarget.entityField(field),
                    "Add entity write to " + task.taskType() + ": " + field));
            }
        }

        for (GlobalKey key : actual.readGlobalKeys()) {
            if (!declared.declaresGlobalRead(key)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_READ, AccessTarget.globalKey(key),
                    "Add global read to " + task.taskType() + ": " + key.value()));
            }
        }

        for (GlobalKey key : actual.writtenGlobalKeys()) {
            if (!declared.declaresGlobalWrite(key)) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_WRITE, AccessTarget.globalKey(key),
                    "Add global write to " + task.taskType() + ": " + key.value()));
            }
        }

        if (!actual.randomCalls().isEmpty() && declared.randomUsage().isEmpty()) {
            for (RandomInstance instance : actual.randomCalls().keySet()) {
                violations.add(violation(tickNumber, task, declared, ViolationType.UNDECLARED_RANDOM_USAGE, AccessTarget.random(instance),
                    "Declare random usage for " + task.taskType() + ": " + instance));
            }
        }

        return List.copyOf(violations);
    }

    private static RWSetViolation violation(
        long tickNumber,
        TaskNode task,
        RWSet declared,
        ViolationType type,
        AccessTarget accessTarget,
        String suggestedFix
    ) {
        return RWSetViolation.create(tickNumber, task.taskId(), task.taskType(), type, accessTarget, declared, suggestedFix);
    }
}
