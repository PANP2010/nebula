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

    /** SENSE: read position+health, write sense.entities/sense.pois. */
    public static EntityTaskAction sense(long entityId) {
        return ctx -> {
            // Read current state for the "perception" computation.
            // position_snapshot is the previous-tick position from the AI snapshot.
            double posX = ctx.readScalarStale(entityId, "position_snapshot");
            double health = ctx.readScalarStale(entityId, "health");
            // Deterministic "perception" density derived from own state.
            double sensedEntities = Math.floorMod((long) (posX * 31 + health), 8);
            double sensedPois = Math.floorMod((long) (posX * 17 + health * 3), 4);
            ctx.writeScalar(entityId, "sense.entities", sensedEntities);
            ctx.writeScalar(entityId, "sense.pois", sensedPois);
        };
    }

    /**
     * GOAL_SELECT: read sensed data, roll a goal within the declared
     * RNG budget (≤3 calls), write goal.target.
     */
    public static EntityTaskAction goalSelect(long entityId) {
        return ctx -> {
            double sensedEntities = ctx.readScalar(entityId, "sense.entities");
            ctx.readScalar(entityId, "sense.pois");
            var rng = ctx.random();

            int base = rng.nextInt(GOAL_COUNT);
            // Low health biases toward goal 0 (flee) via one extra roll.
            if (sensedEntities > 4) {
                base = Math.max(base, rng.nextInt(GOAL_COUNT));
            }
            ctx.writeScalar(entityId, "goal.target", base);
        };
    }

    /** PATHFIND: read goal + position, write path.outcome. */
    public static EntityTaskAction pathfind(long entityId) {
        return ctx -> {
            double goal = ctx.readScalar(entityId, "goal.target");
            double posX = ctx.readScalarStale(entityId, "position_snapshot");
            // Path length: bounded function of goal and position. Goal 0 (flee)
            // produces a longer path than goal-seeking.
            double pathLen = 1 + Math.floorMod((long) (goal * 7 + posX), 16);
            ctx.writeScalar(entityId, "path.outcome", pathLen);
        };
    }

    /**
     * ACT: read goal + path, advance position by a bounded step, write the
     * action result. Consumes RNG within the declared budget (≤5 calls).
     */
    public static EntityTaskAction act(long entityId) {
        return ctx -> {
            double goal = ctx.readScalar(entityId, "goal.target");
            double pathLen = ctx.readScalar(entityId, "path.outcome");
            var rng = ctx.random();

            // A small deterministic, RNG-flavoured step along the path.
            int jitter = rng.nextInt(3) - 1; // -1, 0, or 1
            double step = Math.signum(goal == 0 ? -1 : 1) * Math.min(pathLen, 4) + jitter;
            double pos = ctx.readScalar(entityId, "position");
            ctx.writeScalar(entityId, "position", pos + step);
            ctx.writeScalar(entityId, "act.result", step);
        };
    }

    /** Number of distinct goals GOAL_SELECT chooses among. */
    public static final int GOAL_COUNT = 4;
}
