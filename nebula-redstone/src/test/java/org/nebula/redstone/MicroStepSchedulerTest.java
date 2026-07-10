package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.ParallelTaskRunner;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.actions.RedstoneActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    void layerExecutionGoesThroughRunLayerSeam() throws Exception {
        // B9 D5 enabler: the scheduler must drive each topological layer through
        // TaskRunner.runLayer() (not a private per-task loop) so an intra-layer-
        // parallel runner can plug in. This test asserts the seam is reached with
        // the layer's tasks, and that the per-task run() count still matches.
        List<List<TaskNode>> layersSeen = new ArrayList<>();
        AtomicInteger runCalls = new AtomicInteger();
        TaskRunner recordingRunner = new TaskRunner() {
            @Override
            public void run(TaskNode task) throws Exception {
                runCalls.incrementAndGet();
                task.action().execute();
            }

            @Override
            public void runLayer(List<TaskNode> layer) throws Exception {
                layersSeen.add(List.copyOf(layer));
                TaskRunner.super.runLayer(layer);
            }
        };

        TaskNode taskA = RedstoneTaskFactory.create(
            RedstoneComponentType.REDSTONE_WIRE, POS_A, () -> { });

        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, recordingRunner);

        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(taskA));

        assertEquals(1, result.totalTasks());
        assertEquals(1, layersSeen.size(), "single-task tick drives exactly one layer via runLayer");
        assertEquals(List.of(taskA.taskId()),
            layersSeen.get(0).stream().map(TaskNode::taskId).toList(),
            "the layer passed to runLayer carries the tick's task");
        assertEquals(1, runCalls.get(),
            "default serial runLayer still calls run() once per task — behaviour unchanged");
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

    @Test
    void parallelWrappedRunnerStillCommitsAndCascadesLikeSerial() throws Exception {
        // B9 D5 slice 2 part 2 blocker: the scheduler resolves the committing
        // RedstoneTaskRunner (world() for change-detection + commitLayer() for
        // CAS writes) via runner.unwrap(). Before that seam existed, both were
        // gated on `runner instanceof RedstoneTaskRunner`, so wrapping the
        // runner in a ParallelTaskRunner (the way the plugin will enable the
        // worker pool) would leave world==null and skip commitLayer entirely —
        // the parallel path would compute and write NOTHING. This test proves a
        // parallel-wrapped run produces the SAME whole-line cascade AND CAS
        // writes as the serial run.
        int lineLength = 10;
        WorldPos source = new WorldPos(DIM, 0, 64, 0);

        // Build the identical single-source wire line for both runs.
        java.util.function.Supplier<Map<WorldPos, RedstoneComponentType>> layout = () -> {
            Map<WorldPos, RedstoneComponentType> c = new LinkedHashMap<>();
            c.put(source, RedstoneComponentType.REDSTONE_BLOCK);
            for (int x = 1; x <= lineLength; x++) {
                c.put(new WorldPos(DIM, x, 64, 0), RedstoneComponentType.REDSTONE_WIRE);
            }
            return c;
        };

        // ── Serial baseline ──────────────────────────────────────────────
        RedstoneWorldState serialWorld = seedLine(layout.get(), source, lineLength);
        Map<String, RedstoneTaskAction> serialActions = RedstoneActions.defaults();
        RedstoneTaskRunner serialRunner = new RedstoneTaskRunner(serialWorld, serialActions);
        MicroStepScheduler serialScheduler = new MicroStepScheduler(
            new RedstoneTaskGenerator(layout.get(), serialActions), serialRunner);
        MicroStepScheduler.TickResult serialResult = serialScheduler.executeTick(
            List.of(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE,
                new WorldPos(DIM, 1, 64, 0))));

        // ── Parallel-wrapped run ─────────────────────────────────────────
        RedstoneWorldState parWorld = seedLine(layout.get(), source, lineLength);
        Map<String, RedstoneTaskAction> parActions = RedstoneActions.defaults();
        RedstoneTaskRunner parBase = new RedstoneTaskRunner(parWorld, parActions);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            ParallelTaskRunner parRunner = new ParallelTaskRunner(parBase, pool, 2, 4);
            MicroStepScheduler parScheduler = new MicroStepScheduler(
                new RedstoneTaskGenerator(layout.get(), parActions), parRunner);
            MicroStepScheduler.TickResult parResult = parScheduler.executeTick(
                List.of(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE,
                    new WorldPos(DIM, 1, 64, 0))));

            // The parallel path must actually do the work (not silently no-op).
            assertTrue(parResult.microSteps() > 0,
                "Parallel-wrapped run must still cascade via microstep expansion");
            assertFalse(parResult.hasCommitFailures(),
                "Parallel-wrapped run must commit cleanly (unwrap() found the committer)");

            // Same decay gradient down the line in BOTH runs.
            for (int k = 1; k <= lineLength; k++) {
                WorldPos wire = new WorldPos(DIM, k, 64, 0);
                assertEquals(15 - k, serialWorld.getPowerLevel(wire),
                    "serial wire@" + k);
                assertEquals(serialWorld.getPowerLevel(wire), parWorld.getPowerLevel(wire),
                    "parallel wire@" + k + " must match serial (identical result serial vs parallel)");
            }
            assertEquals(serialResult.totalTasks(), parResult.totalTasks(),
                "parallel run executes the same task count as serial");
        } finally {
            pool.shutdownNow();
        }
    }

    private static RedstoneWorldState seedLine(Map<WorldPos, RedstoneComponentType> layout,
                                               WorldPos source, int lineLength) {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(source, 15);
        for (int x = 1; x <= lineLength; x++) {
            world.putPowerLevel(new WorldPos(DIM, x, 64, 0), 0);
        }
        return world;
    }
}
