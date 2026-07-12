package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.ExplosionActions;
import org.nebula.entity.ExplosionSnapshot;
import org.nebula.entity.ExplosionTaskFactory;
import org.nebula.entity.ExplosionTaskRunner;
import org.nebula.entity.FluidState;
import org.nebula.guard.AccessTarget;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.guard.ViolationType;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the first pure explosion action path records real accesses for B8 C4. */
final class ExplosionRwGuardBridgeTest {

    private static final Logger LOG = Logger.getLogger(ExplosionRwGuardBridgeTest.class.getName());

    private static final WorldPos CENTER = new WorldPos(0, 10, 64, 10);
    private static final WorldPos WEST = new WorldPos(0, 9, 64, 10);
    private static final WorldPos EAST = new WorldPos(0, 11, 64, 10);
    private static final ExplosionSnapshot EXPLOSION =
        new ExplosionSnapshot(CENTER, 4.0f, -1, Set.of(WEST, EAST), List.of());

    private static ActualAccessTrace runAndTrace(TaskNode task, List<WorldPos> blocks) throws Exception {
        FluidState blockState = new FluidState();
        blocks.forEach(pos -> blockState.put(pos, "stone"));
        ExplosionRwGuardHook hook = new ExplosionRwGuardHook(new org.nebula.guard.RWGuardConfig(
            true, 1.0, org.nebula.guard.RWGuardMode.WARN,
            java.nio.file.Path.of("build/test-explosion-rw-violations.jsonl"), 200, false));
        ExplosionTaskRunner runner = new ExplosionTaskRunner(blockState, new EntityPhysicsState(),
            id -> ExplosionActions.blockDestroy(blocks), ExplosionRwGuardTracer.INSTANCE, hook);
        ThreadLocalAccessTrace.reset();
        runner.run(task);
        assertTrue(runner.commit(task.taskId()));
        assertEquals(1, hook.tracedTasks());
        return ThreadLocalAccessTrace.snapshot();
    }

    @Test
    void blockDestroyTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        TaskNode task = ExplosionTaskFactory.createSubDag(EXPLOSION).stream()
            .filter(candidate -> candidate.taskType().equals("EXPLOSION_BLOCK_DESTROY"))
            .findFirst().orElseThrow();
        ActualAccessTrace actual = runAndTrace(task, List.of(WEST, EAST));

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);

        assertTrue(violations.isEmpty(), "explosion action's real accesses must be declared: " + violations);
        assertEquals(Set.of(WEST, EAST), actual.readBlocks());
        assertEquals(Set.of(WEST, EAST), actual.writtenBlocks());
        assertEquals(2, actual.randomCalls().get(RandomInstance.WORLD_RANDOM));
    }

    /**
     * N2 negative control: proves the guard DETECTS missing writes.
     *
     * The factory builds a correct RW-set (all reads + writes).  We deliberately
     * construct an INCOMPLETE RW-set (reads only, no writes) and run the checker
     * against the same actual trace.  The guard MUST report violations > 0 —
     * this is the definitive proof that the positive clean run is not a false
     * positive and that the checker actually fires on broken declarations.
     *
     * Benchmark: redstone broken run reported 5147 violations (drop +X neighbor read),
     * fluid broken run reported 352 violations (delete west neighbor read).  The
     * explosion broken run should surface at least the two missing block writes
     * (WEST + EAST) plus the missing random usage declaration.
     */
    @Test
    void negativeControl_flagsMissingBlockWritesAndRandomUsage() throws Exception {
        TaskNode task = ExplosionTaskFactory.createSubDag(EXPLOSION).stream()
            .filter(candidate -> candidate.taskType().equals("EXPLOSION_BLOCK_DESTROY"))
            .findFirst().orElseThrow();
        ActualAccessTrace actual = runAndTrace(task, List.of(WEST, EAST));

        // Intentionally incomplete: declares reads and nothing else — no writes, no random.
        RWSet incomplete = RWSet.builder()
            .readBlock(WEST)
            .readBlock(EAST)
            // writeBlock(WEST)  ← intentionally omitted
            // writeBlock(EAST)  ← intentionally omitted
            // randomUsage(...)  ← intentionally omitted
            .build();

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, incomplete, actual);

        // We expect at least 3 violations:
        //   1. WEST undeclared write  (from actual.writtenBlocks())
        //   2. EAST undeclared write  (from actual.writtenBlocks())
        //   3. undeclared random usage (WORLD_RANDOM, count 2)
        assertTrue(violations.size() >= 3,
            "incomplete RW-set must produce violations > 0, got: " + violations);

        long writeViolations = violations.stream()
            .filter(v -> v.violationType() == ViolationType.UNDECLARED_WRITE
                && v.accessTarget().type() == org.nebula.guard.AccessTargetType.BLOCK)
            .count();
        assertEquals(2, writeViolations,
            "must flag both WEST and EAST undeclared writes: " + violations);

        long randomViolations = violations.stream()
            .filter(v -> v.violationType() == ViolationType.UNDECLARED_RANDOM_USAGE)
            .count();
        assertTrue(randomViolations >= 1,
            "must flag undeclared random usage: " + violations);

        LOG.info("N2 negative control: explosion incomplete RW-set produced "
            + violations.size() + " violations (write=" + writeViolations
            + ", random=" + randomViolations + ") — guard detection confirmed.");
    }

    @Test
    void bridgeFlagsExactlyAnOmittedBlockWrite() throws Exception {
        RWSet incomplete = RWSet.builder()
            .readBlock(WEST)
            .readBlock(EAST)
            .writeBlock(WEST)
            // east write intentionally omitted
            .randomUsage(new RandomUsage(RandomInstance.WORLD_RANDOM, 2))
            .build();
        TaskNode task = new TaskNode(EXPLOSION.explosionId() + "/destroy-0",
            "EXPLOSION_BLOCK_DESTROY", incomplete, () -> { });

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, incomplete, runAndTrace(task, List.of(WEST, EAST)));

        assertEquals(1, violations.size(), "only the omitted east write should be flagged: " + violations);
        assertEquals(ViolationType.UNDECLARED_WRITE, violations.getFirst().violationType());
        assertEquals(AccessTarget.block(EAST), violations.getFirst().accessTarget());
    }
}
