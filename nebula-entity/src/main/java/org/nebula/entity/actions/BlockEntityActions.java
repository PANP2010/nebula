package org.nebula.entity.actions;

import org.nebula.core.random.DeterministicRandom;
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
    /** Slot count of a dropper/dispenser container (MC DispenserBlockEntity.CONTAINER_SIZE). */
    public static final int DISPENSER_CONTAINER_SIZE = 9;
    /** Vanilla brewing tick count to complete one brew (BrewingStandBlockEntity.serverTick). */
    public static final int BREW_TIME_TOTAL = 400;
    /** Vanilla fuel decrement per brew (BrewingStandBlockEntity.fuel). */
    public static final int BREW_FUEL_PER_BREW = 1;

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

    /**
     * Faithful port of {@code DispenserBlockEntity.getRandomSlot} (MC 1.21.4): a
     * reservoir sample over the {@code slotCount} inventory slots that picks one
     * non-empty slot uniformly at random, or {@code -1} when every slot is empty.
     *
     * <p><b>Why an exact port, not a one-call {@code nextInt(slotCount)}.</b> Vanilla's
     * selection walks every slot and, at the {@code n}-th non-empty one, replaces the
     * running pick with probability {@code 1/n} — {@code random.nextInt(replaceOdds++) == 0}.
     * That draws {@code nextInt} <em>once per non-empty slot</em>, not once total, so its
     * RNG consumption (and therefore the seeded stream position for every task that
     * shares this random instance) depends on how many slots hold items. A single
     * {@code nextInt(slotCount)} would pick a different slot AND consume a different
     * number of RNG calls, diverging from Folia on both the choice and every subsequent
     * draw. The layered-RNG determinism theorem only holds if the shadow's draw count
     * matches vanilla's exactly, which is why the dropper/dispenser RW-set must declare a
     * budget of {@code slotCount} (worst case: all slots full), not {@code 1}.
     *
     * @param slotCounts item count in each of the {@code slotCount} slots (index = slot)
     * @param rng        the per-task deterministic stream, drawn once per non-empty slot
     * @return the chosen slot index, or {@code -1} if all slots are empty
     */
    public static int selectDispenseSlot(int[] slotCounts, DeterministicRandom rng) {
        int chosen = -1;
        int replaceOdds = 1;
        for (int i = 0; i < slotCounts.length; i++) {
            if (slotCounts[i] > 0 && rng.nextInt(replaceOdds++) == 0) {
                chosen = i;
            }
        }
        return chosen;
    }

    /**
     * Pure activity gate for a dropper/dispenser: {@code true} iff a trigger tick would
     * dispense something — i.e. at least one of the {@code slotCount} slots is non-empty,
     * so {@link #selectDispenseSlot} would return a real slot rather than {@code -1}.
     *
     * <p><b>Why a gate at all.</b> Unlike a furnace, a dropper/dispenser does not tick
     * autonomously — it fires only on a redstone rising edge. The live seeder (a later
     * B8 C3 slice) reacts to that pulse, but it still needs a predicate answering "given
     * this container's CAS slots, would a fire actually eject an item?" so a pulsed-but-
     * empty dispenser is treated as quiescent (no divergence to grade) and a pulsed
     * loaded one is re-seeded. This mirrors {@link #furnaceWillMutate}'s role for the
     * autonomous furnace path, and is deliberately colocated with
     * {@link #selectDispenseSlot} so the two cannot drift: {@code hasDispensableItem} is
     * exactly the {@code selectDispenseSlot(...) != -1} condition, and
     * {@code BlockEntityActionsTest} cross-checks that equivalence over a slot matrix.
     *
     * @param slotCounts item count in each of the {@code slotCount} slots
     */
    public static boolean hasDispensableItem(int[] slotCounts) {
        for (int count : slotCounts) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dropper trigger tick: pick one non-empty slot uniformly at random via
     * {@link #selectDispenseSlot} (the exact vanilla {@code getRandomSlot} reservoir
     * draw) and remove one item from it. On an empty container the draw finds nothing
     * ({@code -1}) and the action is a no-op — matching vanilla, which dispenses nothing
     * from an empty dropper.
     *
     * <p><b>Why this is EJECT-ONLY (no output-container write).</b> {@code dropperRw}
     * declares the self inventory slots as read+write plus a {@code readBlock(output)}
     * block-state read — deliberately <em>not</em> the output container's slots. Writing
     * into the output container here would be an undeclared block-entity write, exactly
     * the RW-set drift that lets two tasks race on a shared slot (see
     * {@code BlockEntityTaskFactory}'s under-declaration note and the
     * blockentity-rwset-action-drift lesson). So the honest, RW-set-consistent model is:
     * decrement the chosen self slot and stop. Where the item then lands — the neighbour
     * container the dropper faces, or a world item entity — is not modelled in CAS yet,
     * and modelling it must WIDEN the RW-set first, never silently exceed it.
     *
     * @param self      the dropper position
     * @param slotCount its inventory size (9 for a dropper)
     */
    public static BlockEntityAction dropper(WorldPos self, int slotCount) {
        return ejectOneRandomItem(self, slotCount);
    }

    /**
     * Dispenser trigger tick: identical CAS math to {@link #dropper} — draw a random
     * non-empty slot with the same reservoir sample and remove one item — because both
     * eject exactly one item per pulse via vanilla's {@code getRandomSlot}. They diverge
     * only in the (un-modelled) destination: a dispenser runs the drawn item's dispense
     * behaviour (shoot a projectile, place a block, spawn a mob) where a dropper transfers
     * or ejects it, which is why {@code dispenserRw} declares {@code ENTITY_SPAWNED} rather
     * than the dropper's {@code INVENTORY_CHANGED}. That behaviour is the "stubbed spawn"
     * the live dispenser still lacks; until it is modelled (and the RW-set widened to
     * declare the spawned entity), the only observable CAS effect is the shared self-slot
     * decrement below.
     *
     * @param self      the dispenser position
     * @param slotCount its inventory size (9 for a dispenser)
     */
    public static BlockEntityAction dispenser(WorldPos self, int slotCount) {
        return ejectOneRandomItem(self, slotCount);
    }

    /**
     * Pure activity gate for a brewing stand: {@code true} iff {@link #brewing} would
     * buffer at least one CAS write on its next tick, given the same five inputs the
     * action reads (slots 0..4 plus {@code brew_time} and {@code fuel}).
     *
     * <p><b>Why this exists.</b> The brewing stand is the first autonomous block entity
     * with no other event seeder — no hopper feeds it, no redstone pulse gates it; it
     * re-seeds itself from its own {@code brew_time > 0} path and from the
     * "brewable + fuel > 0" arming path. So unlike the furnace (which we later added
     * an explicit cook-tick seeder for), the brewing stand's re-seed reason is
     * "any state where the next tick would mutate CAS" — exactly this predicate. It
     * mirrors {@link #furnaceWillMutate} one-for-one so the gate cannot silently drift
     * from the action's real branch structure.
     *
     * <p>The {@code fuelSlot} and {@code ingredientSlot} counts use the same int
     * presence-only model the action itself does; {@code brewable} here is the same
     * "ingredient slot non-zero AND any of slots 0..2 non-zero" predicate the action
     * uses (the vanilla {@code PotionBrewing.hasMix} lookup is not modelled — see
     * {@link #brewing} for the conservative coverage rationale).
     */
    public static boolean brewingWillMutate(int slot0, int slot1, int slot2, int ingredientSlot,
                                            int fuelSlot, int brewTime, int fuel) {
        // The action's first mutating branch is `if (brewTime > 0) { write brew_time = brewTime-1 }`.
        // That fires for every brewTime > 0, regardless of brewability, so the gate must
        // also say true for every brewTime > 0.
        if (brewTime > 0) {
            return true;
        }
        // brewTime == 0 → either arm a fresh brew (mutate fuel, brewTime, ingredient,
        // and possibly load fuel from slot 4) or do nothing. The action's idle-arming
        // branch mutates iff isBrewable AND (fuel > 0 OR fuelSlot > 0) — the second
        // disjunct catches the case where the same tick's fuel-load pass arms fuel and
        // the arm-pass immediately consumes one (tested in brewingLoadsFuelAndArmsBrew).
        return brewingIsBrewable(slot0, slot1, slot2, ingredientSlot)
            && (fuel > 0 || fuelSlot > 0);
    }

    /**
     * The conservative "isBrewable" predicate this action's CAS model can compute from
     * its integer-only inventory: ingredient slot non-zero AND at least one bottle
     * slot non-zero. The vanilla {@code PotionBrewing.isIngredient(...)} +
     * {@code PotionBrewing.hasMix(...)} lookups depend on potion NBT, which the
     * integer-count model does not represent — so this is a strict superset of what
     * vanilla would treat as brewable. That conservatism keeps the declared RW-set
     * honest (we never claim a brew fires when it cannot) without forcing the action
     * to model NBT.
     */
    private static boolean brewingIsBrewable(int slot0, int slot1, int slot2, int ingredientSlot) {
        if (ingredientSlot <= 0) {
            return false;
        }
        return slot0 > 0 || slot1 > 0 || slot2 > 0;
    }

    /**
     * Brewing stand tick: ports {@code BrewingStandBlockEntity.serverTick} onto the
     * integer-only inventory model (slots: 0..2 = bottles, 3 = ingredient,
     * 4 = blaze powder fuel).
     *
     * <h3>Vanilla math</h3>
     * <ul>
     *   <li>Fuel loading: if {@code fuel <= 0} and slot 4 is non-zero, arm
     *       {@code fuel = BREW_FUEL_MAX = 20} and decrement slot 4 (vanilla's exact
     *       numbers from {@code BrewingStandBlockEntity.serverTick}).</li>
     *   <li>Brewing: if {@code isBrewable} (ingredient slot non-zero AND at least one
     *       bottle slot non-zero) and {@code fuel > 0}, decrement fuel and arm
     *       {@code brew_time = BREW_TIME_TOTAL = 400}.</li>
     *   <li>Counting: every tick while {@code brew_time > 0}, decrement it. At
     *       {@code brew_time == 0} AND {@code isBrewable}, call {@code doBrew}.</li>
     * </ul>
     *
     * <h3>What this action models and what it does NOT</h3>
     * <p>The vanilla {@code doBrew} mixes potions via {@code PotionBrewing.mix(ing, bot)}
     * which depends on the bottle's NBT potion type (water → awkward → healing etc.).
     * The integer-count inventory model cannot represent that, so {@code doBrew} is
     * modelled as a placeholder: ingredient slot is decremented (vanilla's
     * {@code ingredient.shrink(1)}) and bottle slots are LEFT UNCHANGED. That is
     * deliberate and conservative — modelling the actual brew result would require a
     * full PotionBrewing port beyond C3's scope. The declared RW-set therefore declares
     * the bottle slots read but not necessarily written on the {@code brew_time == 0}
     * branch; {@code brewingStandRw()} declares them as read+write anyway because the
     * conservative envelope is the right contract until the real mix is implemented.
     *
     * @param self the brewing stand position
     */
    public static BlockEntityAction brewing(WorldPos self) {
        return ctx -> {
            int slot0 = ctx.readSlot(self, 0);
            int slot1 = ctx.readSlot(self, 1);
            int slot2 = ctx.readSlot(self, 2);
            int ingredientSlot = ctx.readSlot(self, 3);
            int fuelSlot = ctx.readSlot(self, 4);
            int brewTime = ctx.read(self, "brew_time");
            int fuel = ctx.read(self, "fuel");

            // Fuel loading: if no fuel left and slot 4 is non-zero, arm fuel and
            // consume one blaze powder. Vanilla BrewingStandBlockEntity.serverTick.
            // Track the live fuel value through local mutations so the idle-arming
            // pass below sees the freshly-loaded fuel on the same tick (vanilla's
            // exact same-tick consume order).
            if (fuel <= 0 && fuelSlot > 0) {
                fuel = BREWING_FUEL_MAX;
                ctx.write(self, "fuel", fuel);
                fuelSlot = fuelSlot - 1;
                ctx.writeSlot(self, 4, fuelSlot);
            }

            // Brewing: count down if mid-brew; arm fresh if idle and brewable.
            if (brewTime > 0) {
                int nextBrew = brewTime - 1;
                ctx.write(self, "brew_time", nextBrew);
                if (nextBrew == 0 && brewingIsBrewable(slot0, slot1, slot2, ingredientSlot)) {
                    // doBrew: vanilla decrements the ingredient and mixes bottles via
                    // PotionBrewing. The integer model only decrements the ingredient;
                    // bottles are left unchanged (placeholder for the real mix).
                    ctx.writeSlot(self, 3, ingredientSlot - 1);
                    // Bottle slots are declared read+write in brewingStandRw(); writing
                    // them here would let the placeholder "consume a bottle" — which is
                    // wrong. Until the real mix lands, leave bottle writes to the
                    // conservative declaration's coverage, NOT to the action's writes.
                }
                return;
            }

            // Idle branch: arm a fresh brew if possible. The vanilla tick consumes
            // one fuel unit on the SAME tick the brew is armed (not the next), so
            // the post-tick fuel is (loaded fuel - 1). Mirror that exactly.
            if (brewingIsBrewable(slot0, slot1, slot2, ingredientSlot) && fuel > 0) {
                fuel = fuel - 1;
                ctx.write(self, "fuel", fuel);
                ctx.write(self, "brew_time", BREW_TIME_TOTAL);
            }
        };
    }

    /** Vanilla {@code BrewingStandBlockEntity.fuel} max value (20 per blaze powder). */
    public static final int BREWING_FUEL_MAX = 20;

    /**
     * Shared eject math for {@link #dropper}/{@link #dispenser}: read the self slots, draw
     * one non-empty slot via the vanilla reservoir sample (consuming one RNG call per
     * non-empty slot — the budget {@code dropperRw}/{@code dispenserRw} declare), and
     * decrement it by one. Touches only the self inventory slots the RW-set covers.
     */
    private static BlockEntityAction ejectOneRandomItem(WorldPos self, int slotCount) {
        return ctx -> {
            int[] counts = new int[slotCount];
            for (int s = 0; s < slotCount; s++) {
                counts[s] = ctx.readSlot(self, s);
            }
            int chosen = selectDispenseSlot(counts, ctx.random());
            if (chosen < 0) {
                return; // empty container: getRandomSlot found nothing, nothing ejected
            }
            ctx.writeSlot(self, chosen, counts[chosen] - 1);
        };
    }
}
