package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Entity AI task types (arch doc §7.2).
 *
 * <p>AI tasks form a strict sequential pipeline per tick:
 * SENSE → GOAL_SELECT → PATHFIND → ACT
 *
 * <p>Each stage is DEFERRED: AI computations are expensive and deferred to a
 * lower-priority thread pool. No microstep propagation because AI state changes
 * do not affect redstone or block physics.
 */
public enum AITaskType {

    /** SENSE: reads entity state and nearby environment (player distance, terrain). */
    AI_SENSE(
        "ENTITY_AI_SENSE",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO),

    /** GOAL_SELECT: reads SENSE output, writes goal target. */
    AI_GOAL_SELECT(
        "ENTITY_AI_GOAL_SELECT",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO),

    /** PATHFIND: reads goal target, writes path waypoints. */
    AI_PATHFIND(
        "ENTITY_AI_PATHFIND",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO),

    /** ACT: reads path/goal, writes velocity/position. */
    AI_ACT(
        "ENTITY_AI_ACT",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    AITaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
        this.taskType = taskType;
        this.microStep = microStep;
        this.scc = scc;
    }

    /**
     * Reverse lookup from string task type to enum variant.
     *
     * @param taskType a string like "AI_SENSE", "AI_GOAL_SELECT", "AI_PATHFIND", "AI_ACT"
     * @return the matching enum constant, or {@code null} if unknown
     */
    public static AITaskType fromTaskType(String taskType) {
        for (AITaskType t : values()) {
            if (t.taskType().equals(taskType)) return t;
        }
        return null;
    }
    public String taskType() { return taskType; }
    public MicroStepBehavior microStepBehavior() { return microStep; }
    public SccBehavior sccBehavior() { return scc; }
}
