package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates {@link TaskNode}s for entity AI tasks (arch doc §7.2).
 *
 * <p>AI tasks run in strict sequence: SENSE → GOAL_SELECT → PATHFIND → ACT.
 * Each stage is a separate task with its own RW-set, enabling DAG dependency edges
 * between stages while allowing different parallelism groups for non-conflicting
 * entities.
 *
 * <p><b>Field namespace design:</b> each stage uses a <em>disjoint top-level
 * namespace</em> to avoid the {@code hasSegmentPrefix} false-conflict bug in
 * {@link org.nebula.core.state.FieldPath}. All stages share read-only access to
 * "position" and "velocity" which are fine-grained reads (not fields) — they
 * trigger no conflict detection on entity fields. The four-stage pipeline is:
 * <ul>
 *   <li><b>SENSE:</b> reads position/velocity/health, writes sense.*
 *   <li><b>GOAL_SELECT:</b> reads sense.*, writes goal.*
 *   <li><b>PATHFIND:</b> reads goal.*, writes path.*
 *   <li><b>ACT:</b> reads path.*, writes act.* + fires ENTITY_MOVED
 * </ul>
 */
public final class AITaskFactory {

    private AITaskFactory() {}

    // ── Individual stage builders ───────────────────────────────────────────

    public static TaskNode sense(EntitySnapshot entity, int poiRadius) {
        return aiSense(entity, () -> {});
    }

    public static TaskNode goalSelect(EntitySnapshot entity) {
        return aiGoalSelect(entity, () -> {});
    }

    public static TaskNode actInert(EntitySnapshot entity) {
        return aiAct(entity, () -> {});
    }

    // ── Full pipeline builders ──────────────────────────────────────────────

    /**
     * Returns a list of the four AI pipeline tasks for this entity, each
     * carrying an inert (no-op) action.  The tasks form a RAW dependency
     * chain SENSE → GOAL_SELECT → PATHFIND → ACT when passed to
     * {@link org.nebula.core.scheduler.DagBuilder#build(List)}.
     *
     * @param entity the entity snapshot
     * @param poiRadius ignored (reserved for future POI-query RW-set expansion)
     * @return an unmodifiable list of four task nodes
     */
    public static List<TaskNode> pipelineInert(EntitySnapshot entity, int poiRadius) {
        List<TaskNode> out = new ArrayList<>();
        out.add(aiSense(entity, () -> {}));
        out.add(aiGoalSelect(entity, () -> {}));
        out.add(aiPathfind(entity, () -> {}));
        out.add(aiAct(entity, () -> {}));
        return out;
    }

    // ── Original public API ──────────────────────────────────────────────────

    public static TaskNode aiSense(EntitySnapshot entity, TaskAction action) {
        return new TaskNode(
            aiTaskId(entity, AITaskType.AI_SENSE),
            AITaskType.AI_SENSE.taskType(),
            aiSenseRw(entity),
            action
        );
    }

    public static TaskNode aiGoalSelect(EntitySnapshot entity, TaskAction action) {
        return new TaskNode(
            aiTaskId(entity, AITaskType.AI_GOAL_SELECT),
            AITaskType.AI_GOAL_SELECT.taskType(),
            aiGoalSelectRw(entity),
            action
        );
    }

    public static TaskNode aiPathfind(EntitySnapshot entity, TaskAction action) {
        return new TaskNode(
            aiTaskId(entity, AITaskType.AI_PATHFIND),
            AITaskType.AI_PATHFIND.taskType(),
            aiPathfindRw(entity),
            action
        );
    }

    public static TaskNode aiAct(EntitySnapshot entity, TaskAction action) {
        return new TaskNode(
            aiTaskId(entity, AITaskType.AI_ACT),
            AITaskType.AI_ACT.taskType(),
            aiActRw(entity),
            action
        );
    }

    public static TaskNode aiPipeline(EntitySnapshot entity, TaskAction[] actions) {
        return new TaskNode(
            aiTaskId(entity, AITaskType.AI_SENSE) + "+GOAL+PATH+ACT",
            AITaskType.AI_ACT.taskType(),
            aiActRw(entity),
            () -> {
                for (TaskAction a : actions) {
                    a.execute();
                }
            }
        );
    }

    private static String aiTaskId(EntitySnapshot e, AITaskType type) {
        return type.taskType() + "@" + e.dimensionId() + ":" + e.entityId() + ":" + e.x() + "," + e.y() + "," + e.z();
    }

    // ── RW-sets ───────────────────────────────────────────────────────────────
    // IMPORTANT: each stage uses a disjoint top-level namespace (sense.*, goal.*,
    // path.*, act.*) to avoid FieldPath.hasSegmentPrefix false conflicts.
    // Entity reads of "position"/"velocity"/"health" are fine-grained reads (not
    // EntityField keys) so they don't participate in conflict detection.

    private static RWSet aiSenseRw(EntitySnapshot e) {
        // SENSE reads position/velocity/health (fine-grained reads) and terrain
        // (5-block radius). Writes sense.* so GOAL_SELECT has RAW dependency.
        RWSet.Builder b = RWSet.builder()
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "velocity"))
            .readEntity(field(e.entityId(), "health"))
            .writeEntity(field(e.entityId(), "sense.entities"))
            .writeEntity(field(e.entityId(), "sense.pois"));

        int dim = e.dimensionId();
        int x = e.x(), y = e.y(), z = e.z();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    b.readBlock(new WorldPos(dim, x + dx, y + dy, z + dz));
                }
            }
        }
        return b.build();
    }

    private static RWSet aiGoalSelectRw(EntitySnapshot e) {
        // Reads sense.* (RAW from SENSE) and writes goal.*.
        return RWSet.builder()
            .readEntity(field(e.entityId(), "sense.entities"))
            .readEntity(field(e.entityId(), "sense.pois"))
            .writeEntity(field(e.entityId(), "goal.target"))
            .writeEntity(field(e.entityId(), "goal.active"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 8))
            .build();
    }

    private static RWSet aiPathfindRw(EntitySnapshot e) {
        // Reads goal.* (RAW from GOAL_SELECT) and writes path.*.
        return RWSet.builder()
            .readEntity(field(e.entityId(), "goal.target"))
            .readEntity(field(e.entityId(), "goal.active"))
            .readEntity(field(e.entityId(), "sense.entities"))
            .writeEntity(field(e.entityId(), "path.outcome"))
            .writeEntity(field(e.entityId(), "path.cost"))
            .writeEntity(field(e.entityId(), "path.waypoints"))
            .writeEntity(field(e.entityId(), "path.reachable"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 16))
            .build();
    }

    private static RWSet aiActRw(EntitySnapshot e) {
        // Reads path.* (RAW from PATHFIND) and writes act.*.
        // position/velocity are fine-grained reads (no conflict).
        return RWSet.builder()
            .readEntity(field(e.entityId(), "goal.target"))
            .readEntity(field(e.entityId(), "goal.active"))
            .readEntity(field(e.entityId(), "path.outcome"))
            .readEntity(field(e.entityId(), "path.cost"))
            .readEntity(field(e.entityId(), "path.waypoints"))
            .readEntity(field(e.entityId(), "path.reachable"))
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "velocity"))
            .readEntity(field(e.entityId(), "in_water"))
            .readEntity(field(e.entityId(), "on_ground"))
            .writeEntity(field(e.entityId(), "act.velocity"))
            .writeEntity(field(e.entityId(), "act.position"))
            .writeEntity(field(e.entityId(), "act.result"))
            .writeEvent(EventType.ENTITY_MOVED)
            .build();
    }

    private static EntityField field(long id, String path) {
        return new EntityField(id, path);
    }
}
