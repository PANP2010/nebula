package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

/**
 * Creates {@link TaskNode}s for entity AI tasks (arch doc §7.2).
 *
 * <p>AI tasks run in strict sequence: SENSE → GOAL_SELECT → PATHFIND → ACT.
 * Each stage is a separate task with its own RW-set, enabling DAG dependency edges
 * between stages while allowing different parallelism groups for non-conflicting
 * entities.
 *
 * <p>RW-set templates per stage:
 * <ul>
 *   <li><b>AI_SENSE:</b> reads entity position, health, AI state; reads nearby
 *       terrain for water/ground detection; no writes</li>
 *   <li><b>AI_GOAL_SELECT:</b> reads SENSE output (ai_state), writes goal target</li>
 *   <li><b>AI_PATHFIND:</b> reads goal target + position, writes path waypoints</li>
 *   <li><b>AI_ACT:</b> reads path + goal, writes velocity + position</li>
 * </ul>
 */
public final class AITaskFactory {

    private AITaskFactory() {}

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

    private static RWSet aiSenseRw(EntitySnapshot e) {
        // SENSE reads entity fields and terrain. Reads cover a 5-block radius
        // around the entity for terrain/water detection.
        RWSet.Builder b = RWSet.builder()
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "health"))
            .readEntity(field(e.entityId(), "ai_state"))
            .readEntity(field(e.entityId(), "velocity"));

        int dim = e.dimensionId();
        int x = e.x(), y = e.y(), z = e.z();
        // Terrain reads: 5-block radius for ground/water detection
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
        return RWSet.builder()
            .readEntity(field(e.entityId(), "ai_state"))
            .readEntity(field(e.entityId(), "health"))
            .readEntity(field(e.entityId(), "position"))
            .writeEntity(field(e.entityId(), "goal_target"))
            .writeEntity(field(e.entityId(), "active_goal"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 8))
            .build();
    }

    private static RWSet aiPathfindRw(EntitySnapshot e) {
        return RWSet.builder()
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "goal_target"))
            .readEntity(field(e.entityId(), "velocity"))
            .writeEntity(field(e.entityId(), "path_reachable"))
            .writeEntity(field(e.entityId(), "path_cost"))
            .writeEntity(field(e.entityId(), "path_waypoints"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 16))
            .build();
    }

    private static RWSet aiActRw(EntitySnapshot e) {
        return RWSet.builder()
            .readEntity(field(e.entityId(), "active_goal"))
            .readEntity(field(e.entityId(), "path_reachable"))
            .readEntity(field(e.entityId(), "path_cost"))
            .readEntity(field(e.entityId(), "path_waypoints"))
            .readEntity(field(e.entityId(), "goal_target"))
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "velocity"))
            .readEntity(field(e.entityId(), "in_water"))
            .readEntity(field(e.entityId(), "on_ground"))
            .writeEntity(field(e.entityId(), "velocity"))
            .writeEntity(field(e.entityId(), "position"))
            .build();
    }

    private static EntityField field(long id, String path) {
        return new EntityField(id, path);
    }
}
