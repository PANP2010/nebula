package org.nebula.entity.actions;

import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;

/**
 * AI pipeline actions (arch doc §7.2): SENSE → GOAL_SELECT → PATHFIND → ACT.
 *
 * <p>Each stage reads and writes exactly the {@code ai_state.*} (and position/
 * health) fields its {@code AITaskFactory} RW-set declares, so the four tasks
 * form a RAW dependency chain through shared fields — the DAG serialises them
 * per entity while distinct entities' pipelines run in parallel.
 *
 * <p>The model is deterministic and bounded: SENSE derives a sensed-density
 * scalar from the entity's own state, GOAL_SELECT rolls a goal within its
 * declared RNG budget, PATHFIND derives a path-length scalar, and ACT consumes
 * the path to advance position. Faithful behaviour (real pathfinding, mob goals)
 * is layered on later; this proves the pipeline executes and replays
 * deterministically end to end.
 *
 * <p>Field names mirror {@code AITaskFactory}: {@code "position_snapshot"},
 * {@code "health"}, {@code "ai_state.sensed_entities"},
 * {@code "ai_state.sensed_pois"}, {@code "ai_state.current_goal"},
 * {@code "ai_state.current_path"}, {@code "ai_state.action_result"},
 * {@code "position"}.
 */
public final class AiPipelineActions {

    private AiPipelineActions() {}

    /** SENSE: read position+health, write sensed-entity/POI density scalars. */
    public static EntityTaskAction sense(long entityId) {
        return ctx -> {
            // Under T2+ relaxed determinism the AI perceives the previous
            // tick's snapshot (see EntityTaskContext.readScalarStale and
            // FidelityTier.useStaleAiSnapshot); under T0/T1 we read live as
            // before.
            double posX = ctx.readScalarStale(entityId, "position_snapshot");
            double health = ctx.readScalarStale(entityId, "health");
            // Deterministic "perception" density derived from own state.
            double sensedEntities = Math.floorMod((long) (posX * 31 + health), 8);
            double sensedPois = Math.floorMod((long) (posX * 17 + health * 3), 4);
            ctx.writeScalar(entityId, "ai_state.sensed_entities", sensedEntities);
            ctx.writeScalar(entityId, "ai_state.sensed_pois", sensedPois);
        };
    }

    /**
     * GOAL_SELECT: read sensed data + health, roll a goal within the declared
     * RNG budget (≤3 calls), write the chosen goal.
     */
    public static EntityTaskAction goalSelect(long entityId) {
        return ctx -> {
            double sensedEntities = ctx.readScalar(entityId, "ai_state.sensed_entities");
            ctx.readScalar(entityId, "ai_state.sensed_pois");
            double health = ctx.readScalar(entityId, "health");
            var rng = ctx.random();

            int base = rng.nextInt(GOAL_COUNT);
            // Low health biases toward goal 0 (flee) via one extra roll.
            if (health < 5.0) {
                base = Math.min(base, rng.nextInt(GOAL_COUNT));
            } else if (sensedEntities > 4) {
                // Crowded: re-roll once toward a higher-index goal (engage).
                base = Math.max(base, rng.nextInt(GOAL_COUNT));
            }
            ctx.writeScalar(entityId, "ai_state.current_goal", base);
        };
    }

    /** PATHFIND: read goal + position, write a deterministic path-length scalar. */
    public static EntityTaskAction pathfind(long entityId) {
        return ctx -> {
            double goal = ctx.readScalar(entityId, "ai_state.current_goal");
            double posX = ctx.readScalar(entityId, "position_snapshot");
            // Path length: bounded function of goal and position. Goal 0 (flee)
            // produces a longer path than goal-seeking.
            double pathLen = 1 + Math.floorMod((long) (goal * 7 + posX), 16);
            ctx.writeScalar(entityId, "ai_state.current_path", pathLen);
        };
    }

    /**
     * ACT: read goal + path, advance position by a bounded step, write the
     * action result. Consumes RNG within the declared budget (≤5 calls).
     */
    public static EntityTaskAction act(long entityId) {
        return ctx -> {
            double goal = ctx.readScalar(entityId, "ai_state.current_goal");
            double pathLen = ctx.readScalar(entityId, "ai_state.current_path");
            var rng = ctx.random();

            // A small deterministic, RNG-flavoured step along the path.
            int jitter = rng.nextInt(3) - 1; // -1, 0, or 1
            double step = Math.signum(goal == 0 ? -1 : 1) * Math.min(pathLen, 4) + jitter;
            double pos = ctx.readScalar(entityId, "position");
            ctx.writeScalar(entityId, "position", pos + step);
            ctx.writeScalar(entityId, "ai_state.action_result", step);
        };
    }

    /** Number of distinct goals GOAL_SELECT chooses among. */
    public static final int GOAL_COUNT = 4;
}
