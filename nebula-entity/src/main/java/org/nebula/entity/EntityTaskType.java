package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates entity task types with their static scheduling metadata (arch doc §6.1).
 *
 * <p>Task pipeline per tick for a moving entity:
 * MOVE → COLLISION (pure read, fully parallel) → COLLISION_RESPONSE (serialized per pair)
 *
 * <p>AI_GOAL is deferred — path recalculation resolves next tick.
 * ITEM_PICKUP and DAMAGE are serialized because they modify shared inventory/health fields.
 */
public enum EntityTaskType {

    /**
     * Reads entity position + velocity, reads blocks at destination for terrain collision,
     * writes new position, fires ENTITY_MOVED.
     * PROPAGATES: triggers COLLISION tasks with nearby entities in the same microstep.
     */
    MOVE(
        "ENTITY_MOVE",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /**
     * Pure read: reads positions of two entities to detect bounding-box overlap.
     * No writes → fully parallel with all other COLLISION tasks.
     * Fires ENTITY_MOVED only when overlap detected (to trigger COLLISION_RESPONSE).
     */
    COLLISION(
        "ENTITY_COLLISION",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /**
     * Reads both entities' velocities, writes both velocities after elastic response.
     * SERIALIZED because it writes two entity fields simultaneously.
     */
    COLLISION_RESPONSE(
        "ENTITY_COLLISION_RESPONSE",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    /**
     * Reads entity state + POI queries to select a goal target.
     * DEFERRED: path recalculation resolves in a future tick (expensive).
     */
    AI_GOAL(
        "ENTITY_AI_GOAL",
        MicroStepBehavior.DEFERRED,
        SccBehavior.CONTRACTIBLE),

    /**
     * Reads entity position + item position; writes entity inventory + removes item entity.
     * SERIALIZED because it removes a shared item from the world.
     */
    ITEM_PICKUP(
        "ENTITY_ITEM_PICKUP",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    /**
     * Reads attacker stats + defender health; writes defender health.
     * SERIALIZED because multiple attackers may target the same defender.
     */
    DAMAGE(
        "ENTITY_DAMAGE",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    EntityTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
        this.taskType = taskType;
        this.microStep = microStep;
        this.scc = scc;
    }

    /** Task type string stored in {@link org.nebula.core.scheduler.TaskNode#taskType()}. */
    public String taskType() {
        return taskType;
    }

    public MicroStepBehavior microStepBehavior() {
        return microStep;
    }

    public SccBehavior sccBehavior() {
        return scc;
    }

    /** Reverse lookup by task type string. Returns null if not found. */
    public static EntityTaskType fromTaskType(String taskType) {
        for (EntityTaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
