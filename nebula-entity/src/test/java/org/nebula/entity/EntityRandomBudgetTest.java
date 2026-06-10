package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.random.RandomBudget;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the DG2 random-budget metric through the entity tick loop (arch doc
 * §11.2: "random over-budget re-execution rate &lt;1%").
 *
 * <p>Each RNG-declaring task is allocated a budget from its declared
 * {@code RandomUsage} estimate; the runner records actual consumption and
 * reports the fraction of entities that exceeded budget. These tests assert
 * the metric is computed correctly and that a well-behaved population stays
 * under the DG2 threshold.
 */
class EntityRandomBudgetTest {

    private static final int DIM = 0;
    private static final long WORLD_SEED = 77L;

    /** Declared per-task RNG estimate; budget = max(estimate * 1.5, minBudget). */
    private static final int DECLARED_ESTIMATE = 4;

    /**
     * An RNG-consuming action that makes a fixed number of nextInt() calls,
     * then writes a field so the task is non-empty. Lets a test dial actual
     * consumption above or below the allocated budget deterministically.
     */
    private record FixedCallAction(long entityId, int calls) implements EntityTaskAction {
        @Override
        public void execute(EntityTaskContext ctx) {
            var rng = ctx.random();
            int acc = 0;
            for (int i = 0; i < calls; i++) {
                acc += rng.nextInt(8);
            }
            ctx.writeScalar(entityId, "goal_target", acc % 4);
        }
    }

    /** Builds an AI_GOAL-typed task whose RW-set declares the given RNG estimate. */
    private static TaskNode rngTask(long entityId, int estimate) {
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entityId, "ai_state"))
            .writeEntity(new EntityField(entityId, "goal_target"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, estimate))
            .build();
        String taskId = "ENTITY_AI_GOAL@" + DIM + ":" + entityId;
        return new TaskNode(taskId, "ENTITY_AI_GOAL", rw, () -> {});
    }

    @Test
    void wellBehavedPopulationMeetsDg2Threshold() throws Exception {
        // Allocated budget = max(4 * 1.5, 10) = 10. All entities consume 3 — well
        // within budget → over-budget rate must be 0, comfortably under DG2's 1%.
        double rate = runPopulation(100, entityId -> 3);
        assertEquals(0.0, rate, 1e-9);
        assertTrue(rate < 0.01, "well-behaved population must satisfy DG2 (<1%)");
    }

    @Test
    void overConsumingMinorityIsMeasuredAccurately() throws Exception {
        // Budget = 10. Make exactly 5 of 100 entities consume 12 calls (over),
        // the rest consume 2 (under). Measured rate must be 5/100 = 0.05.
        double rate = runPopulation(100, entityId -> (entityId % 20 == 0) ? 12 : 2);
        assertEquals(0.05, rate, 1e-9);
    }

    @Test
    void exactlyAtBudgetIsNotOverBudget() throws Exception {
        // Consuming exactly the allocated budget (10) counts as COMMIT, not over.
        double rate = runPopulation(50, entityId -> 10);
        assertEquals(0.0, rate, 1e-9);
    }

    /**
     * Runs one tick of {@code count} RNG-declaring entities, each consuming
     * {@code calls.apply(id)} random calls, and returns the over-budget rate.
     */
    private double runPopulation(int count, java.util.function.LongToIntFunction calls) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = i + 1;
            TaskNode t = rngTask(id, DECLARED_ESTIMATE);
            actions.put(t.taskId(), new FixedCallAction(id, calls.applyAsInt(id)));
            tasks.add(t);
        }

        RandomBudget budget = new RandomBudget(); // safety 1.5, minBudget 10
        EntityTaskRunner runner = new EntityTaskRunner(
            state, actions::get, new LayeredRandomSource(WORLD_SEED), budget);
        EntityTickExecutor executor = new EntityTickExecutor(runner);
        executor.executeTick(0L, tasks);

        return runner.currentOverBudgetRate();
    }
}
