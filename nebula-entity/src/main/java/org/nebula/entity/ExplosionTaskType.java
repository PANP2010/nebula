package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates explosion task types matching the sub-DAG layers (arch doc §9.1).
 *
 * <p>Explosion pipeline per detonation:
 * <ol>
 *   <li>RAY_TRACE — parallel ray casts determining which blocks are destroyed</li>
 *   <li>DESTRUCTION_COLLECT — single task collecting ray results, deduplicating</li>
 *   <li>BLOCK_DESTROY — parallel block removals grouped by region</li>
 *   <li>ENTITY_DAMAGE — parallel entity damage/knockback calculations</li>
 * </ol>
 */
public enum ExplosionTaskType {

    RAY_TRACE(
        "EXPLOSION_RAY_TRACE",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    DESTRUCTION_COLLECT(
        "EXPLOSION_DESTRUCTION_COLLECT",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    BLOCK_DESTROY(
        "EXPLOSION_BLOCK_DESTROY",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    ENTITY_DAMAGE(
        "EXPLOSION_ENTITY_DAMAGE",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    ExplosionTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
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

    public static ExplosionTaskType fromTaskType(String taskType) {
        for (ExplosionTaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
