package org.nebula.player;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates player task types with static scheduling metadata.
 *
 * <p>Task pipeline per tick:
 * MOVE → BLOCK_INTERACT (break/place) → INVENTORY_UPDATE
 *
 * <p>Survival mechanics (combat, hunger drain, block interactions) each get
 * their own task type so the DAG can parallelise independent player actions.
 */
public enum PlayerTaskType {

    /**
     * Reads player position + velocity, reads terrain blocks for collision,
     * writes new position, fires PLAYER_MOVED.
     * PROPAGATES: triggers BLOCK_INTERACT if the player interacts with a block.
     */
    MOVE(
        "PLAYER_MOVE",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.SERIALIZED),

    /**
     * Reads player held item + block target, writes block state (break or place),
     * fires BLOCK_UPDATE + PLAYER_INVENTORY_CHANGED.
     * SERIALIZED: break/place is inherently serial per block per tick.
     */
    BLOCK_INTERACT(
        "PLAYER_BLOCK_INTERACT",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.SERIALIZED),

    /**
     * Reads player inventory slot + cursor item, writes slot/cursor changes.
     * SERIALIZED: slot-level conflict detection needed.
     */
    INVENTORY_UPDATE(
        "PLAYER_INVENTORY_UPDATE",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    /**
     * Reads player health + attack damage, writes target entity health.
     * SERIALIZED: multiple players may attack the same entity.
     */
    COMBAT(
        "PLAYER_COMBAT",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED),

    /**
     * Reads player position + nearby entities, writes entity-relative effects
     * (shearing, breeding, feeding).
     * SERIALIZED: shared entity state.
     */
    ENTITY_INTERACT(
        "PLAYER_ENTITY_INTERACT",
        MicroStepBehavior.NONE,
        SccBehavior.SERIALIZED);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    PlayerTaskType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
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

    public static PlayerTaskType fromTaskType(String taskType) {
        for (PlayerTaskType t : values()) {
            if (t.taskType.equals(taskType)) return t;
        }
        return null;
    }
}
