package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

/**
 * CAS-store-backed activity gate for ticking block entities — the connective slice
 * between the pure {@link BlockEntityActions#furnaceWillMutate} predicate (plain ints)
 * and the live cook-tick seeder (B8 C3, named next epic), which must decide each tick
 * whether a furnace is still active straight off the {@link BlockEntityState} that
 * {@code NmsBlockEntityStateBridge.syncFromNms} populated.
 *
 * <p><b>Why this exists.</b> An autonomously-smelting furnace fires no
 * {@code InventoryMoveItemEvent}, so the live seed listener never re-seeds it once it
 * starts cooking and its 200-tick progression goes untracked. The seeder's per-tick
 * sweep needs to read the furnace's five action inputs from CAS and ask "would its next
 * tick mutate anything?" — active furnaces must be re-seeded, quiescent ones are safe
 * for the {@code BE-SETTLED} grader to treat as at-rest.
 *
 * <p><b>Why a dedicated class, not an inlined read.</b> The five field paths this reads
 * ({@code inventory.slots[0..2]}, {@code cook_progress}, {@code fuel_time}) are the exact
 * canonical CAS keys the furnace action and {@code NmsBlockEntityStateBridge} use. Scatter
 * them across the seeder call site and they can silently drift from the action's real
 * footprint — the field-path-mismatch bug class that once silently no-op'd the live hopper
 * sync (see the block-entity-field-path-canonical lesson). Keeping the read in ONE place
 * that delegates to the already-drift-proofed pure predicate localises that knowledge.
 */
public final class BlockEntityActivityGate {

    private BlockEntityActivityGate() {}

    /**
     * Reads a furnace's five action inputs from {@code state} at {@code pos} and returns
     * {@code true} iff its next {@link BlockEntityActions#furnace} tick would buffer at
     * least one CAS write. Unset fields read as 0 (a cold, empty furnace), matching
     * {@link BlockEntityState#get}.
     *
     * @param state the CAS store, typically freshly populated by a region-thread
     *              {@code syncFromNms(pos)} read
     * @param pos   the furnace's world position
     */
    public static boolean furnaceActive(BlockEntityState state, WorldPos pos) {
        int input = slot(state, pos, 0);
        int fuel = slot(state, pos, 1);
        int output = slot(state, pos, 2);
        int cookProgress = field(state, pos, "cook_progress");
        int fuelTime = field(state, pos, "fuel_time");
        return BlockEntityActions.furnaceWillMutate(input, fuel, output, cookProgress, fuelTime);
    }

    /**
     * Reads a brewing stand's seven action inputs from {@code state} at {@code pos}
     * and returns {@code true} iff its next {@link BlockEntityActions#brewing} tick
     * would buffer at least one CAS write — the brewing twin of
     * {@link #furnaceActive}. The seven reads are the exact fields the brewing
     * action consults (slots 0..4, {@code brew_time}, {@code fuel}); bundling them
     * here keeps the per-tick seeder from drifting from the action's footprint.
     *
     * <p><b>Why this exists.</b> Like a smelting furnace, a brewing stand ticks
     * autonomously (no {@code InventoryMoveItemEvent}, no redstone pulse), and the
     * live seed path that reacts only to {@code InventoryMoveItemEvent} therefore
     * never re-seeds a brewing stand after its first registration. Without this
     * gate, a stand armed mid-brew would burn through its 400-tick countdown
     * unticked, then go untraced even after refill. Mirrors {@link #furnaceActive}
     * one-for-one, and {@code BlockEntityActivityGateTest} cross-checks it against
     * actually running the action so this predicate cannot silently drift from the
     * action's branch structure.
     */
    public static boolean brewingActive(BlockEntityState state, WorldPos pos) {
        return BlockEntityActions.brewingWillMutate(
            slot(state, pos, 0), slot(state, pos, 1), slot(state, pos, 2),
            slot(state, pos, 3), slot(state, pos, 4),
            field(state, pos, "brew_time"),
            field(state, pos, "fuel"));
    }

    private static int slot(BlockEntityState state, WorldPos pos, int slot) {
        return field(state, pos, "inventory.slots[" + slot + "]");
    }

    private static int field(BlockEntityState state, WorldPos pos, String name) {
        return state.get(new BlockEntityField(pos, name));
    }
}
