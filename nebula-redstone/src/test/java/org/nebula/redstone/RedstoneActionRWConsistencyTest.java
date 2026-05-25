package org.nebula.redstone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.redstone.actions.ComparatorAction;
import org.nebula.redstone.actions.RedstoneTorchAction;
import org.nebula.redstone.actions.RedstoneWireAction;
import org.nebula.redstone.actions.RepeaterAction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer-A consistency tests (arch doc §14.2 Phase 0 Month 1-2 deliverable):
 * "为每个标注编写单元测试（在隔离环境中验证实际访问不超出声明范围）".
 *
 * <p>For each built-in redstone action, run it through the runner with a tracer
 * that feeds {@link ThreadLocalAccessTrace}, then verify
 * {@link RWSetConsistencyChecker} reports no violations against the declared
 * {@link RedstoneTaskFactory} template.
 */
class RedstoneActionRWConsistencyTest {

    private RedstoneWorldState world;
    private RedstoneAccessTracer tracer;

    @BeforeEach
    void setUp() {
        world = new RedstoneWorldState();
        ThreadLocalAccessTrace.reset();
        tracer = new RedstoneAccessTracer() {
            @Override public void onBlockRead(WorldPos pos) { ThreadLocalAccessTrace.traceBlockRead(pos); }
            @Override public void onBlockWrite(WorldPos pos) { ThreadLocalAccessTrace.traceBlockWrite(pos); }
        };
    }

    private List<RWSetViolation> runAndCheck(RedstoneComponentType type, WorldPos pos,
                                              RedstoneTaskAction action) throws Exception {
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world,
            Map.of(type.taskType(), action), tracer);
        TaskNode task = RedstoneTaskFactory.inert(type, pos);
        runner.run(task);
        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();
        return RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);
    }

    @Test
    void wireStaysWithinDeclaredRWSet() throws Exception {
        WorldPos wire = new WorldPos(0, 10, 64, 10);
        // Seed every neighbour + self so the action exercises every read in its template.
        world.putPowerLevel(wire, 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, 0, 0, -1), 15);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, 0, 0, 1), 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, -1, 0, 0), 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, 1, 0, 0), 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, 0, -1, 0), 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(wire, 0, 1, 0), 0);

        List<RWSetViolation> violations = runAndCheck(
            RedstoneComponentType.REDSTONE_WIRE, wire, new RedstoneWireAction());
        assertTrue(violations.isEmpty(),
            "Wire access pattern must stay within declared RW-set, got: " + violations);
    }

    @Test
    void torchStaysWithinDeclaredRWSet() throws Exception {
        WorldPos torch = new WorldPos(0, 5, 64, 5);
        world.putPowerLevel(torch, 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(torch, 0, -1, 0), 15);

        List<RWSetViolation> violations = runAndCheck(
            RedstoneComponentType.REDSTONE_TORCH, torch, new RedstoneTorchAction());
        assertTrue(violations.isEmpty(),
            "Torch access pattern must stay within declared RW-set, got: " + violations);
    }

    @Test
    void repeaterStaysWithinDeclaredRWSet() throws Exception {
        WorldPos repeater = new WorldPos(0, 0, 64, 0);
        world.putPowerLevel(repeater, 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(repeater, 0, 0, -1), 15); // input
        world.putPowerLevel(RedstoneTaskFactory.neighbour(repeater, 0, 0, 1), 0);   // output

        List<RWSetViolation> violations = runAndCheck(
            RedstoneComponentType.REPEATER, repeater, new RepeaterAction());
        assertTrue(violations.isEmpty(),
            "Repeater access pattern must stay within declared RW-set, got: " + violations);
    }

    @Test
    void comparatorStaysWithinDeclaredRWSet() throws Exception {
        WorldPos comparator = new WorldPos(0, 20, 64, 20);
        world.putPowerLevel(comparator, 0);
        world.putPowerLevel(RedstoneTaskFactory.neighbour(comparator, 0, 0, -1), 12); // front input
        world.putPowerLevel(RedstoneTaskFactory.neighbour(comparator, 0, 0, 1), 0);   // output
        world.putPowerLevel(RedstoneTaskFactory.neighbour(comparator, -1, 0, 0), 7);  // -X side
        world.putPowerLevel(RedstoneTaskFactory.neighbour(comparator, 1, 0, 0), 3);   // +X side

        List<RWSetViolation> violations = runAndCheck(
            RedstoneComponentType.COMPARATOR, comparator, new ComparatorAction());
        assertTrue(violations.isEmpty(),
            "Comparator access pattern must stay within declared RW-set, got: " + violations);
    }
}
