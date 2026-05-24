package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates AI sub-task types (arch doc §7.2).
 *
 * <p>AI pipeline per entity per tick:
 * <ol>
 *   <li>SENSE — gather environment data into AI working memory (wide reads, narrow writes)</li>
 *   <li>GOAL_SELECT — choose an action based on sensed data (reads AI state, writes goal)</li>
 *   <li>PATHFIND — compute navigation path to target (reads blocks, writes path cache)</li>
 *   <li>ACT — execute chosen action (may write position, target health, etc.)</li>
 * </ol>
 */
public enum AITaskType {

    SENSE(
        "AI_SENSE",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    GOAL_SELECT(
        "AI_GOAL_SELECT",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    PATHFIND(
        "AI_PATHFIND",
        MicroStepBehavior.DEFERRED,
        SccBehavior.CONTRACTIBLE),

    ACT(
        "AI_ACT",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    AITaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
        this.taskType = taskType;
        this.microStep = microStep;
        this.scc = scc;
    }

    public String taskType() {
        return taskType;
    }

    public MicroStepBehavior microStepBehavior() {
        return microStep;
    }

    public SccBehavior sccBehavior() {
        return scc;
    }

    public static AITaskType fromTaskType(String taskType) {
        for (AITaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
