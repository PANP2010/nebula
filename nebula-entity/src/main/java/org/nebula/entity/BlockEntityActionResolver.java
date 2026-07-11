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
 * {@link BlockEntityActions} models these behaviours with faithful decompiled constants:
 * <ul>
 *   <li>{@link BlockEntityTaskType#HOPPER} → {@link BlockEntityActions#hopper} —
 *       pull-from-above / push-to-output with the 8-tick transfer cooldown.</li>
 *   <li>{@link BlockEntityTaskType#FURNACE} → {@link BlockEntityActions#furnace} —
 *       200-tick smelt with fuel burn.</li>
 *   <li>{@link BlockEntityTaskType#DROPPER} → {@link BlockEntityActions#dropper} and
 *       {@link BlockEntityTaskType#DISPENSER} → {@link BlockEntityActions#dispenser} —
 *       the vanilla {@code getRandomSlot} reservoir draw + one-item self-slot eject,
 *       consuming the per-task WORLD_RANDOM stream.</li>
 * </ul>
 *
 * <p>{@code DROPPER}/{@code DISPENSER} resolve to their pure eject math
 * ({@link BlockEntityActions#dropper}/{@link BlockEntityActions#dispenser}, the vanilla
 * {@code getRandomSlot} reservoir draw + one-item eject). The prerequisite that gated this
 * flip is now met: the LIVE {@code BlockEntityTaskRunner} is given a
 * {@code LayeredRandomSource} (see {@code NebulaPlugin}'s {@code withSnapshotResolver}
 * wiring, commit 08557e4), and the {@code DROPPER}/{@code DISPENSER} RW-sets declare a
 * {@code RandomUsage(WORLD_RANDOM, slotCount)} (see {@code BlockEntityTaskFactory}), so the
 * runner threads a deterministic per-block stream into {@code ctx.random()} — a loaded
 * dropper resolving here no longer throws on its region thread.
 *
 * <p><b>What this flip does and does NOT claim.</b> It runs the eject math OBSERVE-ONLY: the
 * decremented slot is buffered into the CAS store, but block-entity NMS write-back stays
 * gated OFF ({@code NebulaPlugin.blockEntityWriteBackEnabled()} defaults false), so nothing
 * is mirrored back to Folia and nothing is compared against it. The flip therefore asserts
 * <em>no</em> Folia parity — it is the same honesty level as the hopper/furnace resolver
 * flip (ce6abf2), which also runs un-graded observe-only CAS math, and mirrors how the
 * entity path armed its region-threaded read before (and separately from) its write-back.
 * The "measure before you mirror" trap governs <em>arming write-back</em> or a grader that
 * claims {@code nebula == folia}; it does not govern running the shadow math. Verifying the
 * seeded slot choice against live Folia's dispenser {@code RandomSource}, and only then
 * arming write-back, remain the explicit next slices.
 *
 * <p>{@code BREWING_STAND} resolves to {@link BlockEntityActions#brewing} (B8 C3
 * brewing/dispenser slice): the autonomous math that ports
 * {@code BrewingStandBlockEntity.serverTick} onto the integer-only inventory model. The
 * bottle-mix step is intentionally a placeholder (the integer model cannot represent
 * potion NBT) — see {@link BlockEntityActions#brewing} for the conservative coverage
 * rationale and {@code BlockEntityActivityGateTest} for the gate cross-check that pins
 * the action's branch structure to the {@code brewingWillMutate} predicate.
 */
public final class BlockEntityActionResolver {

    private BlockEntityActionResolver() {}

    /**
     * @param snapshot a ticking block entity's snapshot (fixed position + type +
     *                 facing/slot topology)
     * @return the live action that mutates {@link BlockEntityState} for this block
     *         entity, or {@code null} for a type whose behaviour is not yet modelled
     *         (brewing stand) or a null snapshot
     */
    public static BlockEntityAction resolve(BlockEntitySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return switch (snapshot.type()) {
            case HOPPER -> BlockEntityActions.hopper(
                snapshot.pos(), snapshot.abovePos(), snapshot.outputPos(), snapshot.slotCount());
            case FURNACE -> BlockEntityActions.furnace(snapshot.pos());
            case DROPPER -> BlockEntityActions.dropper(snapshot.pos(), snapshot.slotCount());
            case DISPENSER -> BlockEntityActions.dispenser(snapshot.pos(), snapshot.slotCount());
            case BREWING_STAND -> BlockEntityActions.brewing(snapshot.pos());
        };
    }
}
