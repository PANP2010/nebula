package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.PoiQuery;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creates task nodes for the AI pipeline (arch doc §7.2).
 *
 * <p>Each active entity's AI decomposes into 4 tasks per tick:
 * SENSE → GOAL_SELECT → PATHFIND → ACT. Dependencies between these
 * are captured through shared entity fields in their RW-sets.
 */
public final class AITaskFactory {

    private AITaskFactory() {}

    /**
     * Creates the full AI pipeline (4 tasks) for a single entity.
     * Returned tasks have natural RAW dependencies through shared entity fields.
     */
    public static List<TaskNode> pipeline(EntitySnapshot entity, int perceptionRadius, TaskAction actAction) {
        Objects.requireNonNull(entity);
        List<TaskNode> tasks = new ArrayList<>(4);
        tasks.add(sense(entity, perceptionRadius));
        tasks.add(goalSelect(entity));
        tasks.add(pathfind(entity));
        tasks.add(act(entity, actAction));
        return List.copyOf(tasks);
    }

    public static List<TaskNode> pipelineInert(EntitySnapshot entity, int perceptionRadius) {
        return pipeline(entity, perceptionRadius, () -> {});
    }

    public static TaskNode sense(EntitySnapshot entity, int perceptionRadius) {
        String taskId = AITaskType.SENSE.taskType() + "@" + entity.dimensionId()
            + ":" + entity.entityId();
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entity.entityId(), "position_snapshot"))
            .readEntity(new EntityField(entity.entityId(), "health"))
            .readPoi(new PoiQuery(entity.dimensionId(),
                new WorldPos(entity.dimensionId(), entity.x(), entity.y(), entity.z()),
                perceptionRadius, "ANY"))
            .writeEntity(new EntityField(entity.entityId(), "ai_state.sensed_entities"))
            .writeEntity(new EntityField(entity.entityId(), "ai_state.sensed_pois"))
            .build();
        return new TaskNode(taskId, AITaskType.SENSE.taskType(), rw, () -> {});
    }

    public static TaskNode goalSelect(EntitySnapshot entity) {
        String taskId = AITaskType.GOAL_SELECT.taskType() + "@" + entity.dimensionId()
            + ":" + entity.entityId();
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entity.entityId(), "ai_state.sensed_entities"))
            .readEntity(new EntityField(entity.entityId(), "ai_state.sensed_pois"))
            .readEntity(new EntityField(entity.entityId(), "health"))
            .writeEntity(new EntityField(entity.entityId(), "ai_state.current_goal"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 3))
            .build();
        return new TaskNode(taskId, AITaskType.GOAL_SELECT.taskType(), rw, () -> {});
    }

    public static TaskNode pathfind(EntitySnapshot entity) {
        String taskId = AITaskType.PATHFIND.taskType() + "@" + entity.dimensionId()
            + ":" + entity.entityId();
        WorldPos origin = new WorldPos(entity.dimensionId(), entity.x(), entity.y(), entity.z());
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entity.entityId(), "ai_state.current_goal"))
            .readEntity(new EntityField(entity.entityId(), "position_snapshot"))
            .readBlock(origin)
            .writeEntity(new EntityField(entity.entityId(), "ai_state.current_path"))
            .build();
        return new TaskNode(taskId, AITaskType.PATHFIND.taskType(), rw, () -> {});
    }

    public static TaskNode act(EntitySnapshot entity, TaskAction action) {
        String taskId = AITaskType.ACT.taskType() + "@" + entity.dimensionId()
            + ":" + entity.entityId();
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entity.entityId(), "ai_state.current_goal"))
            .readEntity(new EntityField(entity.entityId(), "ai_state.current_path"))
            .writeEntity(new EntityField(entity.entityId(), "position"))
            .writeEntity(new EntityField(entity.entityId(), "ai_state.action_result"))
            .writeEvent(EventType.ENTITY_MOVED)
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 5))
            .build();
        return new TaskNode(taskId, AITaskType.ACT.taskType(), rw, action);
    }

    public static TaskNode actInert(EntitySnapshot entity) {
        return act(entity, () -> {});
    }
}
