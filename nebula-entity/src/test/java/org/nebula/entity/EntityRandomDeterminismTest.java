package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.entity.actions.EntityGoalSelectAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered-RNG determinism for the entity AI path (arch doc §11, a DG2 gate).
 *
 * <p>The central property: an RNG-consuming task's result depends only on its
 * logical coordinate {@code (worldSeed, tick, entityId)}, never on execution
 * order. These tests prove that executing the same AI goal-selection tasks in
 * arbitrary (shuffled) orders yields byte-identical final state — the
 * precondition for safely parallelising entity AI.
 */
class EntityRandomDeterminismTest {

    private static final int DIM = 0;
    private static final long WORLD_SEED = 0xABCDEFL;
    private static final int ENTITY_COUNT = 16;

    @Test
    void goalSelectionIsOrderIndependent() throws Exception {
        // Run the same goal-selection tasks in natural order, then in several
        // shuffled orders. The resulting goal assignment must be identical.
        Map<Long, Double> goalsNatural = runGoalSelection(false, 0L);

        Random shuffleRng = new Random(1);
        for (int trial = 0; trial < 5; trial++) {
            Map<Long, Double> shuffled = runGoalSelection(true, shuffleRng.nextLong());
            assertEquals(goalsNatural, shuffled,
                "Goal assignment must not depend on task execution order (trial " + trial + ")");
        }
    }

    @Test
    void goalSelectionReproducesAcrossRuns() throws Exception {
        assertEquals(runGoalSelection(false, 0L), runGoalSelection(false, 0L),
            "Two identical runs must produce identical goals");
    }

    @Test
    void differentTicksProduceIndependentStreams() throws Exception {
        // The same entity selecting a goal on different ticks should generally
        // not be locked to one value — confirms tick participates in the seed.
        Map<Long, Double> tick0 = runGoalSelectionAtTick(0L);
        Map<Long, Double> tick1 = runGoalSelectionAtTick(1L);
        assertTrue(!tick0.equals(tick1),
            "Goal selection should vary across ticks (tick is part of the RNG coordinate)");
    }

    @Test
    void rngTaskWithoutSourceThrows() {
        // An RNG-consuming action run without a random source must fail loudly
        // (undeclared RandomUsage), not silently diverge.
        EntityPhysicsState state = new EntityPhysicsState();
        EntityTaskRunner runner = new EntityTaskRunner(state,
            id -> new EntityGoalSelectAction(1L)); // no LayeredRandomSource
        EntityTickExecutor executor = new EntityTickExecutor(runner);

        EntitySnapshot snap = EntitySnapshot.of(1L, 0, 64, 0, DIM);
        TaskNode goal = EntityTaskFactory.aiGoalInert(snap);

        assertThrows(Exception.class, () -> executor.executeTick(0L, List.of(goal)));
    }

    @Test
    void parseEntityId_handlesSingleAndPairFormats() {
        assertEquals(7L, EntityTaskRunner.parseEntityId("ENTITY_AI_GOAL@0:7:10,64,10"));
        assertEquals(3L, EntityTaskRunner.parseEntityId("ENTITY_COLLISION@0:3,9"));
        assertEquals(0L, EntityTaskRunner.parseEntityId("COMPOUND_no_at_sign"));
    }

    @Test
    void rawRunnerOrderDoesNotAffectResult() throws Exception {
        // Bypass the DAG (which sorts by ID) and drive the runner directly in
        // forward then reverse order. Because each task's RNG is seeded from its
        // own (tick, entityId), the committed goals must be identical — proving
        // the order-independence is a property of the seeding, not of the DAG's
        // sorting. This is the precondition for safe parallel execution.
        Map<Long, Double> forward = runRaw(false);
        Map<Long, Double> reverse = runRaw(true);
        assertEquals(forward, reverse,
            "Raw runner execution order must not affect RNG-derived goals");
    }

    private Map<Long, Double> runRaw(boolean reverse) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            long id = i + 1;
            ids.add(id);
            EntitySnapshot snap = EntitySnapshot.of(id, 0, 64, 0, DIM);
            TaskNode goal = EntityTaskFactory.aiGoalInert(snap);
            actions.put(goal.taskId(), new EntityGoalSelectAction(id));
            tasks.add(goal);
        }
        if (reverse) {
            Collections.reverse(tasks);
        }

        EntityTaskRunner runner = new EntityTaskRunner(state, actions::get,
            new LayeredRandomSource(WORLD_SEED));
        runner.beginTick(0L);
        // Single layer (these tasks have disjoint RW-sets): run each, then commit.
        for (TaskNode t : tasks) {
            runner.run(t);
        }
        assertTrue(runner.commitLayer().isEmpty(), "disjoint goals should commit cleanly");

        Map<Long, Double> goals = new TreeMap<>();
        for (long id : ids) {
            goals.put(id, state.getScalar(new EntityField(id, "goal_target")));
        }
        return goals;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<Long, Double> runGoalSelection(boolean shuffle, long shuffleSeed) throws Exception {
        return runGoalSelection(shuffle, shuffleSeed, 0L);
    }

    private Map<Long, Double> runGoalSelectionAtTick(long tick) throws Exception {
        return runGoalSelection(false, 0L, tick);
    }

    private Map<Long, Double> runGoalSelection(boolean shuffle, long shuffleSeed, long tick) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        for (int i = 0; i < ENTITY_COUNT; i++) {
            long id = i + 1;
            ids.add(id);
            EntitySnapshot snap = EntitySnapshot.of(id, 0, 64, 0, DIM);
            TaskNode goal = EntityTaskFactory.aiGoalInert(snap);
            actions.put(goal.taskId(), new EntityGoalSelectAction(id));
            tasks.add(goal);
        }

        if (shuffle) {
            Collections.shuffle(tasks, new Random(shuffleSeed));
        }

        LayeredRandomSource rngSource = new LayeredRandomSource(WORLD_SEED);
        EntityTaskRunner runner = new EntityTaskRunner(state, actions::get, rngSource);
        EntityTickExecutor executor = new EntityTickExecutor(runner);
        executor.executeTick(tick, tasks);

        Map<Long, Double> goals = new TreeMap<>();
        for (long id : ids) {
            goals.put(id, state.getScalar(new EntityField(id, "goal_target")));
        }
        return goals;
    }
}
