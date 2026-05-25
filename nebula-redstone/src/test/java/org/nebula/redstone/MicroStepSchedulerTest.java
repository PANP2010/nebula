package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MicroStepSchedulerTest {

    private static final int DIM = 0;
    private static final WorldPos POS_A = new WorldPos(DIM, 0, 64, 0);
    private static final WorldPos POS_B = new WorldPos(DIM, 0, 64, 1);

    @Test
    void singleTaskExecutesWithoutMicrostep() throws Exception {
        List<String> executionLog = new ArrayList<>();
        TaskNode taskA = RedstoneTaskFactory.create(
            RedstoneComponentType.REDSTONE_WIRE, POS_A,
            () -> executionLog.add("A"));

        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(java.util.Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, t -> t.action().execute());

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(taskA));

        assertEquals(1, result.totalTasks());
        assertEquals(0, result.microSteps());
        assertEquals(List.of("A"), executionLog);
    }

    @Test
    void deferredComponentDoesNotPropagate() throws Exception {
        // Repeater (DEFERRED) writes to output side but should NOT trigger microstep
        List<String> executionLog = new ArrayList<>();
        TaskNode repeater = RedstoneTaskFactory.create(
            RedstoneComponentType.REPEATER, POS_A,
            () -> executionLog.add("repeater"));

        // No known component positions → generator produces nothing regardless
        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(java.util.Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, t -> t.action().execute());

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(repeater));
        assertEquals(0, result.microSteps());
        assertTrue(result.deferredToNextTick().isEmpty());
    }

    @Test
    void emptyDirtySetProducesNoWork() throws Exception {
        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(java.util.Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, t -> t.action().execute());

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of());
        assertEquals(0, result.totalTasks());
    }

    @Test
    void taskIdsFollowConvention() {
        WorldPos pos = new WorldPos(1, 100, 65, 200);
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.COMPARATOR, pos);
        assertEquals("COMPARATOR@1:100,65,200", node.taskId());
    }

    @Test
    void casRetryRecoversStalRead() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(POS_A, 5);

        AtomicInteger executions = new AtomicInteger();

        Map<String, RedstoneTaskAction> actions = Map.of(
            RedstoneComponentType.REDSTONE_WIRE.taskType(), ctx -> {
                int power = ctx.readPowerLevel(POS_A);
                if (executions.incrementAndGet() == 1) {
                    // Simulate concurrent writer: bump version so first commit fails
                    world.putPowerLevel(POS_A, 99);
                }
                ctx.writePowerLevel(POS_A, power + 1);
            }
        );

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, actions);
        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, runner);

        TaskNode task = RedstoneTaskFactory.create(
            RedstoneComponentType.REDSTONE_WIRE, POS_A, () -> {});

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(task));

        assertFalse(result.hasCommitFailures(),
            "Retry should recover the stale read — no permanent failures");
        assertTrue(executions.get() >= 2,
            "Task must have been executed at least twice (initial + retry)");
        assertEquals(100, world.getPowerLevel(POS_A),
            "Final value = 99 (injected by sabotage) + 1 from retry read");
    }

    @Test
    void casRetryExhaustedReportsFailure() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(POS_A, 0);

        Map<String, RedstoneTaskAction> actions = Map.of(
            RedstoneComponentType.REDSTONE_WIRE.taskType(), ctx -> {
                ctx.readPowerLevel(POS_A);
                // Always sabotage: bump version after every read
                world.putPowerLevel(POS_A, world.getPowerLevel(POS_A));
                ctx.writePowerLevel(POS_A, 42);
            }
        );

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, actions);
        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, runner);

        TaskNode task = RedstoneTaskFactory.create(
            RedstoneComponentType.REDSTONE_WIRE, POS_A, () -> {});

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(task));

        assertTrue(result.hasCommitFailures(),
            "Permanently sabotaged CAS should exhaust retries and report failure");
    }
}
