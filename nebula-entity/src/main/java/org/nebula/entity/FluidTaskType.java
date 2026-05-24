package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates fluid task types with scheduling metadata (arch doc §8.1-8.3).
 *
 * <p>Fluid propagation is similar to redstone: each fluid block is a node,
 * propagation goes horizontal-first then down, with depth limits.
 * Water: 7 blocks, Lava: 3 (nether) or 1 (overworld).
 */
public enum FluidTaskType {

    WATER_FLOW(
        "FLUID_WATER_FLOW",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    LAVA_FLOW(
        "FLUID_LAVA_FLOW",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    FLUID_REMOVE(
        "FLUID_REMOVE",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    FluidTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
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

    public static FluidTaskType fromTaskType(String taskType) {
        for (FluidTaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
