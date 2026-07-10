package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.FluidActions;
import org.nebula.entity.FluidSnapshot;
import org.nebula.entity.FluidState;
import org.nebula.entity.FluidTaskFactory;
import org.nebula.entity.FluidTaskRunner;
import org.nebula.guard.AccessTarget;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.guard.ViolationType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the first pure fluid action path records real block accesses for B8 C4. */
final class FluidRwGuardBridgeTest {

    private static final WorldPos SELF = new WorldPos(0, 10, 64, 10);
    private static final FluidSnapshot FLUID = FluidSnapshot.water(SELF, 0, true);

    private static ActualAccessTrace runAndTrace(TaskNode task) throws Exception {
        FluidState state = new FluidState();
        state.put(SELF, FLUID);
        FluidTaskRunner runner = new FluidTaskRunner(state,
            id -> FluidActions.flow(FLUID), FluidRwGuardTracer.INSTANCE);
        ThreadLocalAccessTrace.reset();
        runner.run(task);
        assertTrue(runner.commit(task.taskId()));
        return ThreadLocalAccessTrace.snapshot();
    }

    @Test
    void flowActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        TaskNode task = FluidTaskFactory.flowInert(FLUID);
        ActualAccessTrace actual = runAndTrace(task);

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);

        assertTrue(violations.isEmpty(), "fluid action's real block accesses must be declared: " + violations);
        assertEquals(java.util.Set.of(SELF, FLUID.down(), FLUID.north(), FLUID.south(),
            FLUID.east(), FLUID.west()), actual.readBlocks());
        assertEquals(java.util.Set.of(SELF, FLUID.down()), actual.writtenBlocks());
    }

    @Test
    void bridgeFlagsExactlyAnOmittedDownWrite() throws Exception {
        RWSet incomplete = RWSet.builder()
            .readBlock(SELF)
            .readBlock(FLUID.down())
            .readBlock(FLUID.north())
            .readBlock(FLUID.south())
            .readBlock(FLUID.east())
            .readBlock(FLUID.west())
            .writeBlock(SELF)
            // downward write intentionally omitted
            .writeBlock(FLUID.north())
            .writeBlock(FLUID.south())
            .writeBlock(FLUID.east())
            .writeBlock(FLUID.west())
            .build();
        TaskNode task = new TaskNode(FLUID.taskId(), FLUID.type().taskType(), incomplete, () -> { });

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, incomplete, runAndTrace(task));

        assertEquals(1, violations.size(), "only the omitted downward write should be flagged: " + violations);
        assertEquals(ViolationType.UNDECLARED_WRITE, violations.getFirst().violationType());
        assertEquals(AccessTarget.block(FLUID.down()), violations.getFirst().accessTarget());
    }
}
