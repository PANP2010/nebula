package org.nebula.entity.actions;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;

/**
 * AI goal selection (arch doc §7.2, AI_GOAL) — an RNG-consuming task.
 *
 * <p>Deterministically picks one of a fixed set of goals using the per-task
 * seeded RNG. Reads {@code ai_state} and writes {@code goal_target}, matching
 * the fields the AI_GOAL RW-set declares, and consumes a bounded number of
 * random calls (≤2) well within the declared {@code RandomUsage} budget (8).
 *
 * <p>Because the RNG stream is derived from {@code (tick, entityId)} and not a
 * shared generator, the chosen goal is identical across runs regardless of how
 * the AI tasks are ordered or parallelised — the core layered-RNG property.
 */
public final class EntityGoalSelectAction implements EntityTaskAction {

    /** Number of distinct goals to choose between. */
    public static final int GOAL_COUNT = 4;

    private final long entityId;

    public EntityGoalSelectAction(long entityId) {
        this.entityId = entityId;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        // Read the declared input field (captures its version for CAS).
        ctx.readScalar(entityId, "ai_state");
        DeterministicRandom rng = ctx.random();

        // Roll a base goal (1 call). If it lands in the upper half, re-roll
        // once and keep the lower index — a bounded, declared use of RNG that
        // gives a non-uniform, order-independent distribution.
        int goal = rng.nextInt(GOAL_COUNT);
        if (goal >= GOAL_COUNT / 2) {
            int reroll = rng.nextInt(GOAL_COUNT);
            goal = Math.min(goal, reroll);
        }

        ctx.writeScalar(entityId, "goal_target", goal);
    }
}
