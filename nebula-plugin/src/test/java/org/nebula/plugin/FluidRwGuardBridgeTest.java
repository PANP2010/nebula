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
        FluidRwGuardHook hook = new FluidRwGuardHook(new org.nebula.guard.RWGuardConfig(
            true, 1.0, org.nebula.guard.RWGuardMode.WARN,
            java.nio.file.Path.of("build/test-fluid-rw-violations.jsonl"), 200, false));
        FluidTaskRunner runner = new FluidTaskRunner(state,
            id -> FluidActions.flow(FLUID), FluidRwGuardTracer.INSTANCE, hook);
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
        assertEquals(java.util.Set.of(FLUID.down()), actual.writtenBlocks());
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

    @Test
    void solidNeighbourBlockTypeReadAppearsInTraceAndRwSet() throws Exception {
        TaskNode task = FluidTaskFactory.flowInert(FLUID);
        ActualAccessTrace actual = runWithSolidNeighbourAndTrace(task);

        // A solid (non-fluid) neighbour must still appear in the read trace: the slope-selection
        // action inspects the block type to decide passability, so omitting it from either the
        // RW-set or the trace would be a real bug.
        assertTrue(actual.readBlocks().contains(FLUID.north()),
            "north neighbour (solid in this fixture) must be read: " + actual.readBlocks());
        assertTrue(actual.readBlocks().contains(FLUID.east()),
            "east neighbour (solid in this fixture) must be read: " + actual.readBlocks());
        assertTrue(actual.readBlocks().contains(FLUID.south()),
            "south neighbour (solid in this fixture) must be read: " + actual.readBlocks());
        assertTrue(actual.readBlocks().contains(FLUID.west()),
            "west neighbour (solid in this fixture) must be read: " + actual.readBlocks());

        // Same task's declared RW-set must declare every position the action will read.
        assertTrue(task.declaredRWSet().declaresBlockRead(FLUID.north()));
        assertTrue(task.declaredRWSet().declaresBlockRead(FLUID.east()));
        assertTrue(task.declaredRWSet().declaresBlockRead(FLUID.south()));
        assertTrue(task.declaredRWSet().declaresBlockRead(FLUID.west()));
    }

    private static ActualAccessTrace runWithSolidNeighbourAndTrace(TaskNode task) throws Exception {
        FluidState state = new FluidState();
        state.put(SELF, FLUID);
        // Block down so downward-flow branch is skipped, then mark all four horizontal
        // neighbours as solid (arbitrary non-FluidSnapshot object) to exercise passability.
        state.put(FLUID.down(), new Object());
        state.put(FLUID.north(), new Object());
        state.put(FLUID.south(), new Object());
        state.put(FLUID.east(), new Object());
        state.put(FLUID.west(), new Object());
        FluidRwGuardHook hook = new FluidRwGuardHook(new org.nebula.guard.RWGuardConfig(
            true, 1.0, org.nebula.guard.RWGuardMode.WARN,
            java.nio.file.Path.of("build/test-fluid-rw-violations.jsonl"), 200, false));
        FluidTaskRunner runner = new FluidTaskRunner(state,
            id -> FluidActions.flow(FLUID), FluidRwGuardTracer.INSTANCE, hook);
        ThreadLocalAccessTrace.reset();
        runner.run(task);
        assertTrue(runner.commit(task.taskId()));
        return ThreadLocalAccessTrace.snapshot();
    }
}
