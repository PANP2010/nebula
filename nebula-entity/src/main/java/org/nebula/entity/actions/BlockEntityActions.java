package org.nebula.entity.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityAction;
import org.nebula.entity.BlockEntityContext;

/**
 * Live block-entity tick actions (arch doc §3.3), modelling MC 1.21.4 behaviour
 * over an integer item-count inventory model.
 *
 * <p>Faithful to the decompiled constants:
 * <ul>
 *   <li>Hopper: {@code MOVE_ITEM_SPEED = 8} — moves one item then sets an
 *       8-tick transfer cooldown ({@code HopperBlockEntity}).</li>
 *   <li>Furnace: {@code BURN_TIME_STANDARD = 200} — cooking completes after 200
 *       ticks of accumulated progress while fuel burns
 *       ({@code AbstractFurnaceBlockEntity}).</li>
 * </ul>
 */
public final class BlockEntityActions {

    /** Hopper transfer cooldown in ticks (MC HopperBlockEntity.MOVE_ITEM_SPEED). */
    public static final int HOPPER_COOLDOWN = 8;
    /** Ticks of cook progress to smelt one item (MC BURN_TIME_STANDARD). */
    public static final int COOK_TOTAL = 200;
    /** Fuel ticks granted per fuel item consumed (one standard burn). */
    public static final int FUEL_PER_ITEM = 200;

    private BlockEntityActions() {}

    /**
     * Hopper tick: if off cooldown, pull one item from the slot above into the
     * first non-full self slot, push one item from self into the output, then
     * arm the 8-tick cooldown. Uses the {@code transfer_cooldown} field plus
     * the declared self/above/output inventory slots.
     */
    public static BlockEntityAction hopper(WorldPos self, WorldPos above, WorldPos output, int selfSlots) {
        return ctx -> {
            int cooldown = ctx.read(self, "transfer_cooldown");
            if (cooldown > 0) {
                ctx.write(self, "transfer_cooldown", cooldown - 1);
                return;
            }

            boolean changed = false;

            // Pull: take one item from above slot 0 into the first self slot
            // that has room (model: each slot holds up to 64).
            int aboveCount = ctx.readSlot(above, 0);
            if (aboveCount > 0) {
                for (int s = 0; s < selfSlots; s++) {
                    int c = ctx.readSlot(self, s);
                    if (c < 64) {
                        ctx.writeSlot(self, s, c + 1);
                        ctx.writeSlot(above, 0, aboveCount - 1);
                        changed = true;
                        break;
                    }
                }
            }

            // Push: move one item from the first non-empty self slot to output slot 0.
            for (int s = 0; s < selfSlots; s++) {
                int c = ctx.readSlot(self, s);
                if (c > 0) {
                    int out = ctx.readSlot(output, 0);
                    if (out < 64) {
                        ctx.writeSlot(self, s, c - 1);
                        ctx.writeSlot(output, 0, out + 1);
                        changed = true;
                    }
                    break;
                }
            }

            if (changed) {
                ctx.write(self, "transfer_cooldown", HOPPER_COOLDOWN);
            }
        };
    }

    /**
     * Furnace tick (slots: 0=input, 1=fuel, 2=output). While there is input and
     * either active burn time or available fuel, accumulate cook progress; on
     * reaching {@code COOK_TOTAL}, consume one input and produce one output.
     * Burn time decrements each ticking step and is refilled by consuming fuel.
     */
    public static BlockEntityAction furnace(WorldPos self) {
        return ctx -> {
            int input = ctx.readSlot(self, 0);
            int fuel = ctx.readSlot(self, 1);
            int output = ctx.readSlot(self, 2);
            int cook = ctx.read(self, "cook_progress");
            int fuelTime = ctx.read(self, "fuel_time");

            if (input <= 0 || output >= 64) {
                // Nothing to smelt (or output full): cool down progress, let any
                // remaining burn time tick down.
                if (cook > 0) ctx.write(self, "cook_progress", cook - 1);
                if (fuelTime > 0) ctx.write(self, "fuel_time", fuelTime - 1);
                return;
            }

            // Ensure we are burning: if no fuel time left, consume one fuel item.
            if (fuelTime <= 0) {
                if (fuel <= 0) {
                    // No fuel: progress decays.
                    if (cook > 0) ctx.write(self, "cook_progress", cook - 1);
                    return;
                }
                ctx.writeSlot(self, 1, fuel - 1);
                fuelTime = FUEL_PER_ITEM;
            }

            // Burn one tick of fuel and advance cook progress.
            ctx.write(self, "fuel_time", fuelTime - 1);
            int newCook = cook + 1;
            if (newCook >= COOK_TOTAL) {
                // Smelt complete: consume input, produce output, reset progress.
                ctx.writeSlot(self, 0, input - 1);
                ctx.writeSlot(self, 2, output + 1);
                ctx.write(self, "cook_progress", 0);
            } else {
                ctx.write(self, "cook_progress", newCook);
            }
        };
    }

    /**
     * Pure activity gate for the furnace: {@code true} iff {@link #furnace} would buffer
     * at least one CAS write on its next tick, given the same five inputs the action
     * reads (slot 0=input, 1=fuel, 2=output, plus {@code cook_progress} and
     * {@code fuel_time}).
     *
     * <p><b>Why this exists.</b> An autonomously-smelting furnace fires no
     * {@code InventoryMoveItemEvent}, so the live seed path — which reacts only to that
     * event — never re-seeds a furnace once it starts cooking, and its 200-tick cook
     * progression goes untracked. The live cook-tick seeder (the named next epic) needs
     * exactly this predicate to decide which furnaces are still <em>active</em> and must
     * be re-seeded each tick; and the {@code BE-SETTLED} grader needs it to decide when a
     * furnace has reached true quiescence (no pending mutation), where a
     * {@code nebula != folia} count is a genuine divergence rather than observe-lag.
     *
     * <p>Kept colocated with {@link #furnace} on purpose: a gate that lived elsewhere
     * could silently drift from the action's real branch structure (the
     * under-declaration class of bug — see the RW-set drift lesson). The branches below
     * mirror {@link #furnace} exactly, and {@code BlockEntityActivityGateTest}
     * cross-checks this predicate against actually running the action over a state
     * matrix, so any future edit to one that is not reflected in the other fails a test.
     */
    public static boolean furnaceWillMutate(int input, int fuel, int output,
                                            int cookProgress, int fuelTime) {
        if (input <= 0 || output >= 64) {
            // Idle branch: no input or full output. The action only decays leftover
            // cook progress and burn time, so it mutates iff either is non-zero.
            return cookProgress > 0 || fuelTime > 0;
        }
        // Can smelt: a burning furnace (fuel_time>0) always advances, and a cold one
        // with a fuel item available ignites (consuming it) — both mutate. With neither,
        // the action can only decay existing cook progress.
        return fuelTime > 0 || fuel > 0 || cookProgress > 0;
    }
}
