package org.nebula.entity;

import org.nebula.core.scheduler.TaskGenerator;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Micro-step task generator for entity movement (arch doc §6.3).
 *
 * <p>Pipeline triggered within one tick:
 * <ol>
 *   <li>MOVE task completes → fires ENTITY_MOVED → generate COLLISION tasks for
 *       every tracked entity whose bounding box overlaps the moved entity's
 *       new position bucket.</li>
 *   <li>COLLISION task completes (pure read, no ENTITY_MOVED) → generate a
 *       COLLISION_RESPONSE task for the detected pair.</li>
 *   <li>COLLISION_RESPONSE has no further propagation (NONE microstep).</li>
 * </ol>
 *
 * <p>AI_GOAL, ITEM_PICKUP, and DAMAGE tasks are injected externally (they do
 * not arise from movement propagation) and have DEFERRED / NONE microstep
 * behaviour, so they never appear as downstream tasks here.
 */
public final class EntityTaskGenerator implements TaskGenerator {

    /**
     * Maps entity ID → snapshot for all entities active in the current tick.
     * Provided by the game layer; used to find neighbour candidates.
     */
    private final Map<Long, EntitySnapshot> entityMap;

    /** Collision detection radius in blocks. */
    private static final int COLLISION_RADIUS = 4;

    public EntityTaskGenerator(Map<Long, EntitySnapshot> entityMap) {
        this.entityMap = Objects.requireNonNull(entityMap);
    }

    @Override
    public List<TaskNode> generateFrom(TaskNode completedTask) {
        EntityTaskType type = EntityTaskType.fromTaskType(completedTask.taskType());
        if (type == null) return List.of();

        return switch (type) {
            case MOVE             -> generateCollisionsForMove(completedTask);
            case COLLISION        -> generateResponseForCollision(completedTask);
            default               -> List.of(); // COLLISION_RESPONSE, AI_GOAL, etc.
        };
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private List<TaskNode> generateCollisionsForMove(TaskNode moveTask) {
        if (!moveTask.declaredRWSet().writtenEvents().contains(EventType.ENTITY_MOVED)) {
            return List.of();
        }

        // Extract moved entity ID from the task's write set (first written entity field)
        long moverId = extractEntityId(moveTask, "position");
        if (moverId < 0) return List.of();

        EntitySnapshot mover = entityMap.get(moverId);
        if (mover == null) return List.of();

        List<TaskNode> downstream = new ArrayList<>();
        for (EntitySnapshot other : entityMap.values()) {
            if (other.entityId() == moverId) continue;
            if (withinRadius(mover, other, COLLISION_RADIUS)) {
                downstream.add(EntityTaskFactory.collisionInert(mover, other));
            }
        }
        return List.copyOf(downstream);
    }

    private List<TaskNode> generateResponseForCollision(TaskNode collisionTask) {
        // Collision is a pure-read task — it writes no entity fields.
        // The pair IDs are encoded in the task ID: ENTITY_COLLISION@dim:lo,hi
        long[] pair = extractPairFromTaskId(collisionTask.taskId());
        if (pair == null) return List.of();

        EntitySnapshot a = entityMap.get(pair[0]);
        EntitySnapshot b = entityMap.get(pair[1]);
        if (a == null || b == null) return List.of();

        return List.of(EntityTaskFactory.collisionResponseInert(a, b));
    }

    // ── ID extraction helpers ─────────────────────────────────────────────────

    /**
     * Finds the entity ID from the first written EntityField whose path equals {@code fieldPath}.
     * Returns -1 if not found.
     */
    private static long extractEntityId(TaskNode task, String fieldPath) {
        for (EntityField f : task.declaredRWSet().writtenEntityFields()) {
            if (fieldPath.equals(f.fieldPath().value())) {
                return f.entityId();
            }
        }
        return -1L;
    }

    /**
     * Parses collision task ID: {@code ENTITY_COLLISION@dim:lo,hi}
     * Returns {lo, hi} or null if format doesn't match.
     */
    private static long[] extractPairFromTaskId(String taskId) {
        // format: ENTITY_COLLISION@dim:lo,hi
        int atIdx = taskId.indexOf('@');
        int colonIdx = taskId.indexOf(':', atIdx + 1);
        if (atIdx < 0 || colonIdx < 0) return null;
        String[] parts = taskId.substring(colonIdx + 1).split(",");
        if (parts.length != 2) return null;
        try {
            return new long[]{ Long.parseLong(parts[0]), Long.parseLong(parts[1]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean withinRadius(EntitySnapshot a, EntitySnapshot b, int radius) {
        if (a.dimensionId() != b.dimensionId()) return false;
        int dx = a.x() - b.x();
        int dy = a.y() - b.y();
        int dz = a.z() - b.z();
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }
}
