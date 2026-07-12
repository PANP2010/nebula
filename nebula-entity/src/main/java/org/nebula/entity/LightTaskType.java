package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Light propagation task types (arch doc §10, P2.1).
 */
public enum LightTaskType {

    BLOCK_LIGHT(
        "BLOCK_LIGHT",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO),

    SKY_LIGHT(
        "SKY_LIGHT",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO),

    LIGHT_PROPAGATE(
        "LIGHT_PROPAGATE",
        MicroStepBehavior.DEFERRED,
        SccBehavior.AUTO);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    LightTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
        this.taskType = taskType;
        this.microStep = microStep;
        this.scc = scc;
    }

    public String taskType() { return taskType; }
    public MicroStepBehavior microStepBehavior() { return microStep; }
    public SccBehavior sccBehavior() { return scc; }
}
