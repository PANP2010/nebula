package org.nebula.redstone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneTaskRunnerTest {

    private static final WorldPos POS_A = new WorldPos(0, 10, 64, 20);
    private static final WorldPos POS_B = new WorldPos(0, 11, 64, 20);
    private static final WorldPos POS_C = new WorldPos(0, 12, 64, 20);

    private RedstoneWorldState world;

    @BeforeEach
    void setUp() {
        world = new RedstoneWorldState();
        world.putPowerLevel(POS_A, 0);
        world.putPowerLevel(POS_B, 5);
    }

    @Test
    void taskReadsAndWritesThroughContext() throws Exception {
        RedstoneTaskAction action = ctx -> {
            int current = ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), current + 15);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REDSTONE_WIRE", action));
        TaskNode task = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_A);

        runner.run(task);

        // Not yet committed
        assertEquals(0, world.getPowerLevel(POS_A));

        // Commit
        List<String> failed = runner.commitLayer();
        assertTrue(failed.isEmpty());
        assertEquals(15, world.getPowerLevel(POS_A));
    }

    @Test
    void multipleTasksInLayerCommitTogether() throws Exception {
        RedstoneTaskAction wireAction = ctx -> {
            ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), 15);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REDSTONE_WIRE", wireAction));

        TaskNode taskA = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_A);
        TaskNode taskB = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_B);

        runner.run(taskA);
        runner.run(taskB);

        // Both uncommitted
        assertEquals(0, world.getPowerLevel(POS_A));
        assertEquals(5, world.getPowerLevel(POS_B));

        List<String> failed = runner.commitLayer();
        assertTrue(failed.isEmpty());
        assertEquals(15, world.getPowerLevel(POS_A));
        assertEquals(15, world.getPowerLevel(POS_B));
    }

    @Test
    void commitDetectsStaleRead() throws Exception {
        RedstoneTaskAction action = ctx -> {
            ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), 15);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REDSTONE_WIRE", action));
        TaskNode task = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_A);

        runner.run(task);

        // Simulate concurrent modification before commit
        world.putPowerLevel(POS_A, 99);

        List<String> failed = runner.commitLayer();
        assertEquals(1, failed.size());
        assertEquals(task.taskId(), failed.get(0));
        assertEquals(99, world.getPowerLevel(POS_A), "Concurrent write preserved");
    }

    @Test
    void resetLayerClearsSnapshots() throws Exception {
        RedstoneTaskAction action = ctx -> {
            ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), 15);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REDSTONE_WIRE", action));
        TaskNode task = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_A);

        runner.run(task);
        runner.resetLayer();

        // Nothing to commit after reset
        List<String> failed = runner.commitLayer();
        assertTrue(failed.isEmpty());
        assertEquals(0, world.getPowerLevel(POS_A), "No write applied");
    }

    @Test
    void fallbackToBuiltInActionWhenNotRegistered() throws Exception {
        boolean[] ran = {false};
        TaskNode task = RedstoneTaskFactory.create(
            RedstoneComponentType.REDSTONE_WIRE, POS_A, () -> ran[0] = true);

        // No action registered for REDSTONE_WIRE → fallback
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world);
        runner.run(task);

        assertTrue(ran[0]);
    }

    @Test
    void internalStateWrittenThroughContext() throws Exception {
        RedstoneTaskAction action = ctx -> {
            ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), 10);
            ctx.writeInternalState(ctx.position(), "delay_counter", 3);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REPEATER", action));
        TaskNode task = RedstoneTaskFactory.inert(RedstoneComponentType.REPEATER, POS_A);

        runner.run(task);
        List<String> failed = runner.commitLayer();
        assertTrue(failed.isEmpty());

        assertEquals(10, world.getPowerLevel(POS_A));
        assertEquals(3, world.getInternalState(POS_A, "delay_counter"));
    }

    @Test
    void schedulerIntegrationWithRedstoneTaskRunner() throws Exception {
        RedstoneTaskAction wireAction = ctx -> {
            ctx.readPowerLevel(ctx.position());
            ctx.writePowerLevel(ctx.position(), 15);
        };

        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of("REDSTONE_WIRE", wireAction));
        RedstoneTaskGenerator gen = new RedstoneTaskGenerator(Map.of());
        MicroStepScheduler scheduler = new MicroStepScheduler(gen, runner);

        TaskNode task = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, POS_A);
        MicroStepScheduler.TickResult result = scheduler.executeTick(List.of(task));

        assertEquals(1, result.totalTasks());
        assertFalse(result.hasCommitFailures());
        assertEquals(15, world.getPowerLevel(POS_A));
    }
}
