package org.nebula.entity;

import org.nebula.entity.actions.BlockEntityActions;

/**
 * Resolves the live {@link BlockEntityAction} for a ticking block entity from its
 * {@link BlockEntitySnapshot} — the block-entity analogue of
 * {@code NebulaPlugin.resolveEntityAction}, and the pure prerequisite for swapping
 * the inert block-entity resolver ({@code snapshot -> BlockEntityTaskFactory.inert})
 * for one that runs real hopper/furnace item math on the CAS DAG (B8 C3).
 *
 * <h3>Why key on the snapshot, not the task ID</h3>
 * The block-entity task ID grammar is {@code TYPE@dim:x,y,z} (see
 * {@link BlockEntitySnapshot#taskId()}) — it carries the type and position but
 * <em>not</em> the hopper's facing/output direction or slot count. A hopper's
 * transfer math needs its {@code abovePos}/{@code outputPos}/{@code slotCount},
 * all of which live on the snapshot and none of which survive into the task ID.
 * So unlike {@code resolveEntityAction} (which re-decodes an
 * {@code ENTITY_MOVE@dim:id:x,y,z} ID because a mob's action needs only its id +
 * dimension), the block-entity action must be resolved from the snapshot the hook
 * already accumulated, before it is flattened to a task ID.
 *
 * <h3>Coverage</h3>
 * {@link BlockEntityActions} currently models the two behaviours with faithful
 * decompiled constants:
 * <ul>
 *   <li>{@link BlockEntityTaskType#HOPPER} → {@link BlockEntityActions#hopper} —
 *       pull-from-above / push-to-output with the 8-tick transfer cooldown.</li>
 *   <li>{@link BlockEntityTaskType#FURNACE} → {@link BlockEntityActions#furnace} —
 *       200-tick smelt with fuel burn.</li>
 * </ul>
 * {@code DROPPER}, {@code DISPENSER} and {@code BREWING_STAND} have no action math
 * in {@link BlockEntityActions} yet, so they resolve to {@code null} — an honest
 * no-op the runner already treats as "declared its RW-set, mutated nothing" — until
 * that math is written. Returning a fabricated action for them would be exactly the
 * kind of unverified claim this project exists to avoid.
 */
public final class BlockEntityActionResolver {

    private BlockEntityActionResolver() {}

    /**
     * @param snapshot a ticking block entity's snapshot (fixed position + type +
     *                 facing/slot topology)
     * @return the live action that mutates {@link BlockEntityState} for this block
     *         entity, or {@code null} for a type whose behaviour is not yet modelled
     *         (dropper/dispenser/brewing stand) or a null snapshot
     */
    public static BlockEntityAction resolve(BlockEntitySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return switch (snapshot.type()) {
            case HOPPER -> BlockEntityActions.hopper(
                snapshot.pos(), snapshot.abovePos(), snapshot.outputPos(), snapshot.slotCount());
            case FURNACE -> BlockEntityActions.furnace(snapshot.pos());
            // Not yet modelled in BlockEntityActions — no-op rather than a fabricated action.
            case DROPPER, DISPENSER, BREWING_STAND -> null;
        };
    }
}
