package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskFactory;
import org.nebula.redstone.RedstoneTaskRunner;
import org.nebula.redstone.RedstoneWorldState;
import org.nebula.redstone.actions.RedstoneActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8 task B2b, unit slice: prove {@link RedstoneRwGuardHook} is wired correctly into
 * {@link RedstoneTaskRunner} — the exact seam the live guard installs on real Folia.
 *
 * <p>{@link RedstoneRwGuardBridgeTest} already proves the tracer→checker path detects
 * drift; this test proves the <em>per-task hook boundary</em> works end to end through
 * the runner: it fires for each dispatched task, brackets that task's trace, checks it
 * against the task's own declared RW-set, counts what it sampled, and writes real
 * violations to the configured JSONL log. Without the hook firing at
 * {@code run(TaskNode)}, the live guard would trace nothing and report a false clean.
 */
final class RedstoneRwGuardHookTest {

    private static WorldPos p(int x, int y, int z) {
        return new WorldPos(0, x, y, z);
    }

    private static RWGuardConfig config(Path log) {
        // sampling 1.0 → check every task (the B2b first-run posture).
        return new RWGuardConfig(true, 1.0, RWGuardMode.WARN, log, 200, false);
    }

    @Test
    void cleanWireTaskIsSampledAndTracesZeroViolations(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("rw-violations.jsonl");
        RedstoneRwGuardHook hook = new RedstoneRwGuardHook(config(log));

        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(p(1, -60, 0), 15); // powered neighbour → wire recomputes + writes
        world.putPowerLevel(p(0, -60, 0), 0);

        Map<String, RedstoneTaskAction> registry = RedstoneActions.defaults();
        RedstoneTaskRunner runner = new RedstoneTaskRunner(
            world, registry, RedstoneRwGuardTracer.INSTANCE, hook);

        runner.run(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, p(0, -60, 0)));

        assertEquals(1, hook.tracedTasks(), "the hook must sample and check the dispatched task");
        assertEquals(0, hook.violationCount(),
            "the known-complete wire RW-set must trace clean against the real action's accesses");
        assertFalse(Files.exists(log), "a clean run must write no violation log");
    }

    @Test
    void undeclaredAccessIsCaughtByTheHookAndWrittenToTheLog(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("rw-violations.jsonl");
        RedstoneRwGuardHook hook = new RedstoneRwGuardHook(config(log));

        WorldPos pos = p(0, -60, 0);
        WorldPos omitted = p(1, -60, 0); // +X neighbour the wire action really reads
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(omitted, 15);
        world.putPowerLevel(pos, 0);

        // A deliberately-incomplete WIRE RW-set that omits the +X neighbour read.
        RWSet incomplete = RWSet.builder()
            .readBlock(pos)
            .readBlock(p(0, -60, -1))
            .readBlock(p(0, -60, 1))
            .readBlock(p(-1, -60, 0))
            // p(1,-60,0) intentionally omitted
            .readBlock(p(0, -61, 0))
            .readBlock(p(0, -59, 0))
            .writeBlock(pos)
            .build();
        TaskNode task = new TaskNode(
            RedstoneTaskFactory.taskId(RedstoneComponentType.REDSTONE_WIRE, pos),
            RedstoneComponentType.REDSTONE_WIRE.taskType(),
            incomplete,
            () -> { });

        Map<String, RedstoneTaskAction> registry = RedstoneActions.defaults();
        RedstoneTaskRunner runner = new RedstoneTaskRunner(
            world, registry, RedstoneRwGuardTracer.INSTANCE, hook);

        runner.run(task);

        assertEquals(1, hook.tracedTasks(), "the hook must have checked the one dispatched task");
        assertTrue(hook.violationCount() >= 1,
            "the omitted +X neighbour read must be flagged as an undeclared access");
        assertTrue(Files.exists(log), "violations must be appended to the configured JSONL log");
        List<String> lines = Files.readAllLines(log);
        assertFalse(lines.isEmpty(), "the violation log must contain at least one JSON line");
        assertTrue(lines.getFirst().contains("UNDECLARED_READ"),
            "the logged violation must be the undeclared read; got: " + lines.getFirst());
    }

    @Test
    void samplingRateZeroSkipsCheckingButStillRunsTheTask(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("rw-violations.jsonl");
        // sampling 0.0 → never trace/check; the task must still execute normally.
        RWGuardConfig off = new RWGuardConfig(true, 0.0, RWGuardMode.WARN, log, 200, false);
        RedstoneRwGuardHook hook = new RedstoneRwGuardHook(off);

        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(p(1, -60, 0), 15);
        WorldPos pos = p(0, -60, 0);
        world.putPowerLevel(pos, 0);

        Map<String, RedstoneTaskAction> registry = RedstoneActions.defaults();
        RedstoneTaskRunner runner = new RedstoneTaskRunner(
            world, registry, RedstoneRwGuardTracer.INSTANCE, hook);

        runner.run(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, pos));
        runner.commitLayer();

        assertEquals(0, hook.tracedTasks(), "sampling 0.0 must check nothing");
        assertEquals(0, hook.violationCount(), "no checks → no violations");
        // The action still ran: the wire recomputed its power from the powered neighbour.
        assertEquals(14, world.getPowerLevel(pos),
            "the task must still execute (wire takes neighbour 15 decayed by 1) even when unsampled");
    }
}
