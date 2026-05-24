package org.nebula.entity;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates block-entity task types with scheduling metadata (arch doc §3.3, §14.3 Month 4-6).
 *
 * <p>Block entities operate on fixed positions with slot-level granularity:
 * <ul>
 *   <li>HOPPER: transfers items between containers — reads source slots, writes both source and dest slots</li>
 *   <li>FURNACE: smelts items — reads input+fuel slots, writes output+fuel slots + cook progress</li>
 *   <li>CHEST: no autonomous tick, but participates in hopper chains as a read/write target</li>
 *   <li>BREWING_STAND: brews potions — reads ingredient+fuel, writes output slots + brew time</li>
 * </ul>
 */
public enum BlockEntityTaskType {

    HOPPER(
        "BLOCK_ENTITY_HOPPER",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    FURNACE(
        "BLOCK_ENTITY_FURNACE",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    BREWING_STAND(
        "BLOCK_ENTITY_BREWING_STAND",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    DROPPER(
        "BLOCK_ENTITY_DROPPER",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    DISPENSER(
        "BLOCK_ENTITY_DISPENSER",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    BlockEntityTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
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

    public static BlockEntityTaskType fromTaskType(String taskType) {
        for (BlockEntityTaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
