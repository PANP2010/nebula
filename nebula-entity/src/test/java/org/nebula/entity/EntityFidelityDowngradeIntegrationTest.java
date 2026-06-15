package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.FidelityTier;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.random.RandomBudget;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.FidelityDowngradeController;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end link between the live DG2 over-budget metric and the fidelity
 * downgrade controller (arch doc §16.2; NEBULA-PATCH-2026-001 §变更四).
 *
 * <p>{@link RandomBudget} allocates an <em>adaptive</em> budget: it tracks each
 * entity's historical max consumption, so steady high RNG usage is absorbed
 * (the budget rises to meet it) and does <em>not</em> trigger a downgrade. The
 * T0→T1 trigger fires only when consumption keeps <em>outrunning</em> that
 * adaptive budget — a sustained spike. These tests pin down both behaviours by
 * feeding the runner's measured over-budget rate into the controller.
 */
class EntityFidelityDowngradeIntegrationTest {

    private static final int DIM = 0;

    /** An action whose RNG-call count for a given tick is supplied per-entity. */
    private record VariableCallAction(long entityId, java.util.function.LongToIntFunction callsForTick)
            implements EntityTaskAction {
        // The current tick is injected via a thread-local set by the harness.
        @Override
        public void execute(EntityTaskContext ctx) {
            var rng = ctx.random();
            int calls = callsForTick.applyAsInt(CURRENT_TICK.get());
            int acc = 0;
            for (int i = 0; i < calls; i++) {
                acc += rng.nextInt(8);
            }
            ctx.writeScalar(entityId, "goal_target", acc % 4);
        }
    }

    private static final ThreadLocal<Long> CURRENT_TICK = ThreadLocal.withInitial(() -> 0L);

    private static TaskNode rngTask(long entityId, int estimate) {
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entityId, "ai_state"))
            .writeEntity(new EntityField(entityId, "goal_target"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, estimate))
            .build();
        return new TaskNode("ENTITY_AI_GOAL@" + DIM + ":" + entityId, "ENTITY_AI_GOAL", rw, () -> {});
    }

    @Test
    void escalatingConsumptionOutrunsAdaptiveBudgetAndDowngrades() throws Exception {
        // The adaptive budget is max(1.5 * historicalMax, 10), so a tick is
        // over-budget only when its consumption exceeds 1.5x the previous tick's.
        // Growing ~1.8x per tick (8, 14, 25, 45, ...) stays ahead of the budget
        // every tick → 10 consecutive over-budget ticks → T0→T1.
        FidelityTier tier = runScenario(tick -> (int) Math.round(8 * Math.pow(1.8, tick)), 13);
        assertEquals(FidelityTier.T1, tier,
            "consumption that keeps outrunning the adaptive budget must downgrade T0→T1");
    }

    @Test
    void steadyHighConsumptionIsAbsorbedByAdaptiveBudget() throws Exception {
        // A high but CONSTANT 30 calls/tick: after the first tick the adaptive
        // budget rises to absorb it, so the over-budget rate falls back to 0 and
        // the 10-consecutive-tick trigger never completes → stays T0.
        FidelityTier tier = runScenario(tick -> 30, 50);
        assertEquals(FidelityTier.T0, tier,
            "steady high usage is absorbed by the adaptive budget — no downgrade");
    }

    @Test
    void wellBehavedPopulationStaysAtT0() throws Exception {
        FidelityTier tier = runScenario(tick -> 2, 50);
        assertEquals(FidelityTier.T0, tier, "within-budget population must hold T0");
    }

    /**
     * Runs {@code ticks} ticks of a 20-entity population whose per-tick RNG
     * consumption is {@code callsForTick}, feeding the measured over-budget rate
     * into the downgrade controller each tick.
     */
    private FidelityTier runScenario(java.util.function.LongToIntFunction callsForTick, int ticks) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long id = i + 1;
            TaskNode t = rngTask(id, 4);
            actions.put(t.taskId(), new VariableCallAction(id, callsForTick));
            tasks.add(t);
        }

        RandomBudget budget = new RandomBudget();
        EntityTaskRunner runner = new EntityTaskRunner(
            state, actions::get, new LayeredRandomSource(123L), budget);
        EntityTickExecutor executor = new EntityTickExecutor(runner);
        FidelityDowngradeController controller = new FidelityDowngradeController(FidelityTier.T0);

        FidelityTier tier = FidelityTier.T0;
        for (long tick = 0; tick < ticks; tick++) {
            CURRENT_TICK.set(tick);
            executor.executeTick(tick, tasks);
            double rate = runner.currentOverBudgetRate();
            tier = controller.reportTick(rate, 10); // MSPT healthy; isolate the RNG trigger
        }
        return tier;
    }
}

