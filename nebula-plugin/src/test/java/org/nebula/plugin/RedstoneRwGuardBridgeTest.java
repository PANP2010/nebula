package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.guard.AccessTarget;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.guard.ViolationType;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskFactory;
import org.nebula.redstone.RedstoneTaskRunner;
import org.nebula.redstone.RedstoneWorldState;
import org.nebula.redstone.actions.RedstoneActions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8 task B2, pure-unit slice: prove the {@link RedstoneRwGuardTracer} bridge makes
 * the RW-guard actually see the live redstone actions' block accesses, and that the
 * known-complete redstone RW-sets in {@link RedstoneTaskFactory} trace <em>clean</em>
 * against those real accesses through {@link RWSetConsistencyChecker}.
 *
 * <p>This is the honest de-risk before the live-Folia guard run: it converts "redstone
 * RW-sets are known-complete" from an asserted claim into a checker-verified one, using
 * the exact tracer B2's live wiring will install. Without this bridge the guard traces
 * nothing on live redstone (the actions record through {@code RedstoneAccessTracer}, not
 * {@link ThreadLocalAccessTrace}) and would report a false "zero violations".
 *
 * <p>Each test runs a <em>real</em> action through {@link RedstoneTaskRunner} (which
 * builds the {@code RedstoneTaskContext} with our bridge tracer), then checks the
 * captured {@link ThreadLocalAccessTrace} against the real factory RW-set for that type.
 */
final class RedstoneRwGuardBridgeTest {

    private static WorldPos p(int x, int y, int z) {
        return new WorldPos(0, x, y, z);
    }

    /**
     * Runs the live action for {@code type} at {@code pos} through the real runner with
     * the guard bridge tracer installed, then returns the checker's verdict against the
     * real factory RW-set. The world is pre-seeded so the action performs a real state
     * change (exercising the write path, not just reads).
     */
    private static List<RWSetViolation> runAndCheck(RedstoneComponentType type, WorldPos pos,
                                                    RedstoneWorldState world) throws Exception {
        Map<String, RedstoneTaskAction> registry = RedstoneActions.defaults();
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, registry, RedstoneRwGuardTracer.INSTANCE);
        TaskNode task = RedstoneTaskFactory.inert(type, pos);

        ThreadLocalAccessTrace.reset();
        runner.run(task);
        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();

        return RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);
    }

    @Test
    void wireActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        WorldPos pos = p(0, -60, 0);
        RedstoneWorldState world = new RedstoneWorldState();
        // A powered source neighbour so the wire actually recomputes and writes.
        world.putPowerLevel(p(1, -60, 0), 15);
        world.putPowerLevel(pos, 0);

        List<RWSetViolation> violations = runAndCheck(RedstoneComponentType.REDSTONE_WIRE, pos, world);

        assertTrue(violations.isEmpty(),
            "wire action's real block accesses must all be declared in wireRw(); got: " + violations);
    }

    @Test
    void torchActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        WorldPos pos = p(0, -60, 0);
        RedstoneWorldState world = new RedstoneWorldState();
        // Unpowered attached block below → torch should turn on (write path).
        world.putPowerLevel(p(0, -61, 0), 0);
        world.putPowerLevel(pos, 0);

        List<RWSetViolation> violations = runAndCheck(RedstoneComponentType.REDSTONE_TORCH, pos, world);

        assertTrue(violations.isEmpty(),
            "torch action's real block accesses must all be declared in torchRw(); got: " + violations);
    }

    @Test
    void repeaterActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        WorldPos pos = p(0, -60, 0);
        RedstoneWorldState world = new RedstoneWorldState();
        // Powered input behind → repeater begins its delayed change (reads input+output+internal).
        world.putPowerLevel(p(0, -60, -1), 15);
        world.putPowerLevel(p(0, -60, 1), 0);
        world.putPowerLevel(pos, 0);

        List<RWSetViolation> violations = runAndCheck(RedstoneComponentType.REPEATER, pos, world);

        assertTrue(violations.isEmpty(),
            "repeater action's real block accesses must all be declared in repeaterRw(); got: " + violations);
    }

    @Test
    void comparatorActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        WorldPos pos = p(0, -60, 0);
        RedstoneWorldState world = new RedstoneWorldState();
        // Front stronger than sides → compare-mode output changes (reads front+2 sides+self+output).
        world.putPowerLevel(p(0, -60, -1), 15); // front input
        world.putPowerLevel(p(-1, -60, 0), 3);  // left side
        world.putPowerLevel(p(1, -60, 0), 2);   // right side
        world.putPowerLevel(p(0, -60, 1), 0);   // output
        world.putPowerLevel(pos, 0);

        List<RWSetViolation> violations = runAndCheck(RedstoneComponentType.COMPARATOR, pos, world);

        assertTrue(violations.isEmpty(),
            "comparator action's real block accesses must all be declared in comparatorRw(); got: " + violations);
    }

    @Test
    void bridgeHasRealDetectionPowerWhenAnAccessIsUndeclared() throws Exception {
        // Negative control: prove the bridge+checker actually catch drift, so the clean
        // results above are meaningful and not a silently-empty trace. Declare a WIRE
        // RW-set that OMITS one neighbour the wire action really reads (the +X neighbour),
        // then confirm the checker flags exactly that undeclared block read.
        WorldPos pos = p(0, -60, 0);
        WorldPos omitted = p(1, -60, 0); // +X neighbour — read by RedstoneWireAction, dropped below
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(omitted, 15);
        world.putPowerLevel(pos, 0);

        // Build a deliberately-incomplete RW-set: self + 5 of 6 neighbours, dropping +X.
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
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, registry, RedstoneRwGuardTracer.INSTANCE);

        ThreadLocalAccessTrace.reset();
        runner.run(task);
        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();

        List<RWSetViolation> violations = RWSetConsistencyChecker.check(0L, task, incomplete, actual);

        assertEquals(1, violations.size(),
            "exactly the one omitted neighbour read must be flagged; got: " + violations);
        RWSetViolation v = violations.getFirst();
        assertEquals(ViolationType.UNDECLARED_READ, v.violationType());
        assertEquals(AccessTarget.block(omitted), v.accessTarget(),
            "the flagged access must be the +X neighbour the RW-set dropped");
    }
}
