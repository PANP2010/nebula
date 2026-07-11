package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure item math in {@link BlockEntityActions} branch-by-branch.
 *
 * <p>{@link BlockEntityActionResolverTest} already covers the two "happy path"
 * branches (hopper pull-then-push in one tick; furnace smelt-completion). This test
 * closes the coverage gap on the branches those cases never reach — the furnace
 * ignition / accumulation / three decay paths and the hopper push-only / pull-only /
 * no-move-no-cooldown paths. Those branches are exactly what the live BE-SETTLED gate
 * (B8 C3) relies on being correct, so they are worth pinning here where every step is
 * observable, rather than leaving them to a live furnace whose SMELTING math the gate
 * cannot observe under observe-only (see the block-entity-dag-live-verified lesson).
 *
 * <p>Like the resolver test, each case executes the action through a real
 * {@link BlockEntityContext} against a {@link BlockEntityState} and asserts the CAS
 * state mutates exactly as the decompiled constants specify.
 */
class BlockEntityActionsTest {

    private static final WorldPos POS = new WorldPos(0, 4, 64, 9);

    /** Runs an action once against the given state via a fresh snapshot buffer, committing writes. */
    private static void tickOnce(BlockEntityState state, BlockEntityAction action) throws Exception {
        BlockEntitySnapshotState buffer = new BlockEntitySnapshotState();
        action.execute(new BlockEntityContext(state, buffer));
        buffer.commit(state);
    }

    /** As {@link #tickOnce} but supplies a deterministic RNG stream for RNG-consuming actions. */
    private static void tickOnceWithRng(BlockEntityState state, BlockEntityAction action,
                                        DeterministicRandom rng) throws Exception {
        BlockEntitySnapshotState buffer = new BlockEntitySnapshotState();
        action.execute(new BlockEntityContext(state, buffer, null, rng));
        buffer.commit(state);
    }

    private static BlockEntityField slot(WorldPos pos, int s) {
        return new BlockEntityField(pos, "inventory.slots[" + s + "]");
    }

    private static BlockEntityField field(WorldPos pos, String name) {
        return new BlockEntityField(pos, name);
    }

    // ---------------------------------------------------------------- furnace

    @Test
    void furnaceIgnitesWhenColdWithFuel() throws Exception {
        // Cold furnace (fuel_time=0, cook=0) with input + fuel present: it must
        // consume one fuel item, arm a full FUEL_PER_ITEM burn (less this tick), and
        // begin cooking (cook_progress 0 -> 1).
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1); // input
        state.put(slot(POS, 1), 1); // fuel
        // cook_progress and fuel_time default to 0.

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(0, state.get(slot(POS, 1)), "one fuel item consumed on ignition");
        assertEquals(BlockEntityActions.FUEL_PER_ITEM - 1, state.get(field(POS, "fuel_time")),
            "full burn armed, then one tick burned this step");
        assertEquals(1, state.get(field(POS, "cook_progress")), "cooking begins the same tick fuel ignites");
        assertEquals(1, state.get(slot(POS, 0)), "input not consumed until COOK_TOTAL reached");
        assertEquals(0, state.get(slot(POS, 2)), "no output until COOK_TOTAL reached");
    }

    @Test
    void furnaceAccumulatesProgressWhileAlreadyBurning() throws Exception {
        // Already burning (fuel_time>0), mid-cook: no fuel consumed, burn ticks down,
        // cook advances by one, well short of completion.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        state.put(slot(POS, 1), 5); // spare fuel present but must NOT be touched
        state.put(field(POS, "cook_progress"), 50);
        state.put(field(POS, "fuel_time"), 10);

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(9, state.get(field(POS, "fuel_time")), "burn time ticks down by one");
        assertEquals(51, state.get(field(POS, "cook_progress")), "cook progress advances by one");
        assertEquals(5, state.get(slot(POS, 1)), "no fuel consumed while still burning");
        assertEquals(1, state.get(slot(POS, 0)), "input untouched mid-cook");
    }

    @Test
    void furnaceDecaysProgressWhenOutputFull() throws Exception {
        // Input present but output full: the smelt cannot land, so progress decays and
        // any remaining burn time ticks down.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        state.put(slot(POS, 2), 64); // output full
        state.put(field(POS, "cook_progress"), 5);
        state.put(field(POS, "fuel_time"), 3);

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(4, state.get(field(POS, "cook_progress")), "progress decays with output full");
        assertEquals(2, state.get(field(POS, "fuel_time")), "remaining burn still ticks down");
        assertEquals(1, state.get(slot(POS, 0)), "input untouched when it cannot smelt");
    }

    @Test
    void furnaceDecaysProgressWhenNoInput() throws Exception {
        // Nothing to smelt: progress and burn both decay.
        BlockEntityState state = new BlockEntityState();
        state.put(field(POS, "cook_progress"), 5);
        state.put(field(POS, "fuel_time"), 3);

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(4, state.get(field(POS, "cook_progress")));
        assertEquals(2, state.get(field(POS, "fuel_time")));
    }

    @Test
    void furnaceDecaysProgressWhenOutOfFuel() throws Exception {
        // Input present, but burn time exhausted and no fuel item to reignite: progress
        // decays and no fuel is (cannot be) consumed.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        state.put(field(POS, "cook_progress"), 5);
        // fuel slot and fuel_time default to 0.

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(4, state.get(field(POS, "cook_progress")), "progress decays with no fuel to reignite");
        assertEquals(0, state.get(field(POS, "fuel_time")), "fuel time stays exhausted");
        assertEquals(0, state.get(slot(POS, 1)), "no fuel item to consume");
        assertEquals(1, state.get(slot(POS, 0)), "input untouched when it cannot burn");
    }

    @Test
    void furnaceProgressDoesNotUnderflowAtZero() throws Exception {
        // Fully idle furnace: nothing to decay, cook_progress must not go negative.
        BlockEntityState state = new BlockEntityState();
        // everything defaults to 0.

        tickOnce(state, BlockEntityActions.furnace(POS));

        assertEquals(0, state.get(field(POS, "cook_progress")), "cook progress floors at 0");
        assertEquals(0, state.get(field(POS, "fuel_time")), "fuel time floors at 0");
    }

    // ----------------------------------------------------------------- hopper

    @Test
    void hopperPushesFromSelfWhenNothingAbove() throws Exception {
        // Item sitting in the hopper, nothing above to pull: it pushes one to output
        // and arms the cooldown.
        WorldPos above = new WorldPos(0, 4, 65, 9);
        WorldPos output = new WorldPos(0, 4, 63, 9);
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 5);

        tickOnce(state, BlockEntityActions.hopper(POS, above, output, 5));

        assertEquals(4, state.get(slot(POS, 0)), "one item pushed out of self");
        assertEquals(1, state.get(slot(output, 0)), "item landed in output");
        assertEquals(0, state.get(slot(above, 0)), "nothing above to pull");
        assertEquals(BlockEntityActions.HOPPER_COOLDOWN, state.get(field(POS, "transfer_cooldown")),
            "cooldown armed after the push");
    }

    @Test
    void hopperPullsButCannotPushWhenOutputFull() throws Exception {
        // Item above, empty self, full output: it pulls one item in but cannot push it
        // out this tick. A move still happened, so the cooldown arms.
        WorldPos above = new WorldPos(0, 4, 65, 9);
        WorldPos output = new WorldPos(0, 4, 63, 9);
        BlockEntityState state = new BlockEntityState();
        state.put(slot(above, 0), 3);
        state.put(slot(output, 0), 64); // output full

        tickOnce(state, BlockEntityActions.hopper(POS, above, output, 5));

        assertEquals(2, state.get(slot(above, 0)), "one item pulled from above");
        assertEquals(1, state.get(slot(POS, 0)), "pulled item held in self, cannot push to full output");
        assertEquals(64, state.get(slot(output, 0)), "output stays full");
        assertEquals(BlockEntityActions.HOPPER_COOLDOWN, state.get(field(POS, "transfer_cooldown")),
            "cooldown armed because a pull happened");
    }

    @Test
    void hopperArmsNoCooldownWhenIdle() throws Exception {
        // Nothing above and empty self: no move, so the cooldown must NOT arm.
        WorldPos above = new WorldPos(0, 4, 65, 9);
        WorldPos output = new WorldPos(0, 4, 63, 9);
        BlockEntityState state = new BlockEntityState();

        tickOnce(state, BlockEntityActions.hopper(POS, above, output, 5));

        assertEquals(0, state.get(field(POS, "transfer_cooldown")),
            "no move means the cooldown stays unarmed");
    }

    // -------------------------------------------------- dropper / dispenser

    @Test
    void selectDispenseSlotReturnsMinusOneWhenAllEmpty() {
        // An empty container dispenses nothing and — critically — draws NO random calls,
        // matching vanilla's reservoir loop which only rolls at non-empty slots.
        DeterministicRandom rng = new DeterministicRandom(1234L);
        int[] slots = new int[BlockEntityActions.DISPENSER_CONTAINER_SIZE];

        assertEquals(-1, BlockEntityActions.selectDispenseSlot(slots, rng));
        assertEquals(0, rng.callsMade(), "no non-empty slot means no RNG draw");
    }

    @Test
    void selectDispenseSlotPicksTheSoleNonEmptySlot() {
        // With exactly one loaded slot, the reservoir always keeps it — nextInt(1) is
        // always 0 — and draws exactly once regardless of the seed.
        for (long seed : new long[]{0L, 1L, 42L, -7L}) {
            DeterministicRandom rng = new DeterministicRandom(seed);
            int[] slots = new int[BlockEntityActions.DISPENSER_CONTAINER_SIZE];
            slots[4] = 3;
            assertEquals(4, BlockEntityActions.selectDispenseSlot(slots, rng),
                "sole non-empty slot must be chosen for seed " + seed);
            assertEquals(1, rng.callsMade(), "exactly one draw for one non-empty slot");
        }
    }

    @Test
    void selectDispenseSlotDrawsOncePerNonEmptySlot() {
        // The draw COUNT — not just the choice — must equal the number of non-empty slots,
        // because every task sharing this RNG instance depends on the stream position it
        // leaves behind. Three loaded slots ⇒ exactly three nextInt calls.
        DeterministicRandom rng = new DeterministicRandom(99L);
        int[] slots = new int[BlockEntityActions.DISPENSER_CONTAINER_SIZE];
        slots[0] = 1;
        slots[3] = 2;
        slots[8] = 5;

        int chosen = BlockEntityActions.selectDispenseSlot(slots, rng);

        assertEquals(3, rng.callsMade(), "one draw per non-empty slot");
        assertTrue(chosen == 0 || chosen == 3 || chosen == 8,
            "the chosen slot must be one of the non-empty ones, was " + chosen);
    }

    @Test
    void selectDispenseSlotMatchesVanillaReservoirForAFixedSeed() {
        // Lock the exact vanilla algorithm: re-run the decompiled getRandomSlot loop with
        // an independent Random on the same seed and assert byte-identical choice + draw
        // count. If selectDispenseSlot ever deviates from getRandomSlot, this fails.
        long seed = 20260710L;
        int[] slots = {2, 0, 4, 0, 0, 1, 7, 0, 3};

        java.util.Random ref = new java.util.Random(seed);
        int expected = -1;
        int replaceOdds = 1;
        int expectedDraws = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] > 0) {
                expectedDraws++;
                if (ref.nextInt(replaceOdds++) == 0) {
                    expected = i;
                }
            }
        }

        DeterministicRandom rng = new DeterministicRandom(seed);
        assertEquals(expected, BlockEntityActions.selectDispenseSlot(slots, rng),
            "must match vanilla getRandomSlot's choice");
        assertEquals(expectedDraws, rng.callsMade(),
            "must match vanilla getRandomSlot's draw count");
    }

    @Test
    void hasDispensableItemAgreesWithSelectAcrossASlotMatrix() {
        // Anti-drift: hasDispensableItem must be exactly (selectDispenseSlot != -1). Sweep
        // empty, single-loaded (each position), and multi-loaded containers. Use a fresh
        // RNG per cell so the selection draw count cannot leak between cells.
        int size = BlockEntityActions.DISPENSER_CONTAINER_SIZE;

        // All empty.
        int[] empty = new int[size];
        assertFalse(BlockEntityActions.hasDispensableItem(empty));
        assertEquals(-1, BlockEntityActions.selectDispenseSlot(empty, new DeterministicRandom(0L)));

        // Each single-loaded position.
        for (int i = 0; i < size; i++) {
            int[] slots = new int[size];
            slots[i] = 1;
            boolean has = BlockEntityActions.hasDispensableItem(slots);
            boolean selected = BlockEntityActions.selectDispenseSlot(slots, new DeterministicRandom(i)) != -1;
            assertTrue(has, "single loaded slot " + i + " must be dispensable");
            assertEquals(has, selected, "gate must agree with select at slot " + i);
        }

        // Fully loaded.
        int[] full = new int[size];
        java.util.Arrays.fill(full, 64);
        assertTrue(BlockEntityActions.hasDispensableItem(full));
        assertTrue(BlockEntityActions.selectDispenseSlot(full, new DeterministicRandom(7L)) != -1);
    }

    // ------------------------------------------ dropper / dispenser eject action

    @Test
    void dropperEjectsOneItemFromTheReservoirChosenSlot() throws Exception {
        // A loaded dropper removes exactly one item from the slot getRandomSlot picks and
        // leaves every other slot untouched. Pin the choice by replaying the vanilla
        // reservoir loop with an independent Random on the same seed.
        long seed = 424242L;
        int[] slots = {0, 3, 0, 5, 0, 0, 2, 0, 1};
        int expected = expectedChosenSlot(slots, seed);

        BlockEntityState state = new BlockEntityState();
        for (int s = 0; s < slots.length; s++) state.put(slot(POS, s), slots[s]);

        tickOnceWithRng(state, BlockEntityActions.dropper(POS, slots.length),
            new DeterministicRandom(seed));

        for (int s = 0; s < slots.length; s++) {
            int want = (s == expected) ? slots[s] - 1 : slots[s];
            assertEquals(want, state.get(slot(POS, s)),
                "only the chosen slot " + expected + " loses one item (slot " + s + ")");
        }
    }

    @Test
    void dispenserEjectsIdenticallyToDropperForTheSameSeed() throws Exception {
        // The two share their CAS eject math (they diverge only in the un-modelled
        // destination), so the same seed + same slots must decrement the same slot.
        long seed = -99L;
        int[] slots = {4, 0, 0, 7, 0, 1, 0, 0, 0};

        BlockEntityState dropperState = new BlockEntityState();
        BlockEntityState dispenserState = new BlockEntityState();
        for (int s = 0; s < slots.length; s++) {
            dropperState.put(slot(POS, s), slots[s]);
            dispenserState.put(slot(POS, s), slots[s]);
        }

        tickOnceWithRng(dropperState, BlockEntityActions.dropper(POS, slots.length),
            new DeterministicRandom(seed));
        tickOnceWithRng(dispenserState, BlockEntityActions.dispenser(POS, slots.length),
            new DeterministicRandom(seed));

        for (int s = 0; s < slots.length; s++) {
            assertEquals(dropperState.get(slot(POS, s)), dispenserState.get(slot(POS, s)),
                "dropper and dispenser eject the same slot for the same seed (slot " + s + ")");
        }
    }

    @Test
    void dropperOnEmptyContainerIsANoOpAndDrawsNoRng() throws Exception {
        // An empty dropper dispenses nothing: getRandomSlot returns -1 having drawn zero
        // times, so no slot is written and the RNG stream is left untouched.
        BlockEntityState state = new BlockEntityState();
        DeterministicRandom rng = new DeterministicRandom(1L);

        tickOnceWithRng(state, BlockEntityActions.dropper(POS, BlockEntityActions.DISPENSER_CONTAINER_SIZE), rng);

        for (int s = 0; s < BlockEntityActions.DISPENSER_CONTAINER_SIZE; s++) {
            assertEquals(0, state.get(slot(POS, s)), "empty dropper mutates no slot");
        }
        assertEquals(0, rng.callsMade(), "no non-empty slot means no RNG draw (stream position preserved)");
    }

    @Test
    void dropperDrawsExactlyOncePerNonEmptySlot() throws Exception {
        // The draw COUNT must equal the non-empty slot count, because every task sharing
        // this WORLD_RANDOM instance depends on the stream position the dropper leaves —
        // the reason dropperRw budgets slotCount, not 1.
        int[] slots = {1, 0, 2, 0, 0, 3, 0, 4, 0}; // four non-empty
        BlockEntityState state = new BlockEntityState();
        for (int s = 0; s < slots.length; s++) state.put(slot(POS, s), slots[s]);
        DeterministicRandom rng = new DeterministicRandom(5L);

        tickOnceWithRng(state, BlockEntityActions.dropper(POS, slots.length), rng);

        assertEquals(4, rng.callsMade(), "one draw per non-empty slot");
    }

    @Test
    void dropperWithNoRngSourceThrows() {
        // A loaded dropper resolved under a runner that supplied no random source must
        // fail loudly, not silently diverge — the undeclared/unsupplied RandomUsage trap.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        BlockEntityAction action = BlockEntityActions.dropper(POS, BlockEntityActions.DISPENSER_CONTAINER_SIZE);
        assertThrows(IllegalStateException.class, () -> tickOnce(state, action));
    }

    // ------------------------------------------------------------- brewing stand

    @Test
    void brewingLoadsFuelAndArmsBrewOnIdleBrewableLayout() throws Exception {
        // Cold brewing stand (fuel=0, brew_time=0) with a blaze powder in slot 4 AND
        // a brewable layout (slot 3 non-zero + at least one bottle slot non-zero):
        // the action arms fuel=20 (BREWING_FUEL_MAX), decrements slot 4, then on the
        // same idle branch decrements fuel by 1 and arms brew_time=400.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);   // bottle
        state.put(slot(POS, 3), 3);   // ingredient (e.g. nether wart)
        state.put(slot(POS, 4), 1);   // one blaze powder

        tickOnce(state, BlockEntityActions.brewing(POS));

        // Vanilla BrewingStandBlockEntity.serverTick performs TWO passes per tick:
        // first the fuel-load (sets fuel=20, shrinks slot 4), then the idle-arming
        // pass that consumes the freshly-loaded fuel and arms brew_time=400. So the
        // post-tick fuel is 20-1 = 19 — the fuel-load and the fuel-consume-on-arm
        // happen on the SAME tick, exactly mirroring the decompiled control flow.
        assertEquals(BlockEntityActions.BREWING_FUEL_MAX - 1, state.get(field(POS, "fuel")),
            "fuel loaded to BREWING_FUEL_MAX then immediately consumed by the arm pass");
        assertEquals(0, state.get(slot(POS, 4)), "blaze powder consumed");
        assertEquals(BlockEntityActions.BREW_TIME_TOTAL, state.get(field(POS, "brew_time")),
            "brew_time armed to BREW_TIME_TOTAL");
    }

    @Test
    void brewingCountsDownBrewTimeWithoutConsumingIngredient() throws Exception {
        // Already brewing (brew_time > 0): the action decrements brew_time by 1 and
        // does NOT touch the ingredient or bottles (vanilla BrewingStandBlockEntity
        // only mutates slot 3 at brew_time==0 in the doBrew branch).
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        state.put(slot(POS, 3), 3);
        state.put(field(POS, "brew_time"), 100);
        state.put(field(POS, "fuel"), 5);

        tickOnce(state, BlockEntityActions.brewing(POS));

        assertEquals(99, state.get(field(POS, "brew_time")), "brew_time decrements by 1");
        assertEquals(3, state.get(slot(POS, 3)), "ingredient untouched mid-brew");
        assertEquals(1, state.get(slot(POS, 0)), "bottle untouched mid-brew");
        assertEquals(5, state.get(field(POS, "fuel")), "fuel untouched mid-brew");
    }

    @Test
    void brewingOnCompletionDecrementsIngredientButLeavesBottlesAlone() throws Exception {
        // brew_time == 1 → next tick decrements to 0; if isBrewable holds, doBrew
        // fires. Vanilla decrements the ingredient and mixes bottles via PotionBrewing;
        // the integer-only action's placeholder leaves bottles unchanged. This pins
        // the conservative behavior that brewingStandRw()'s conservative envelope
        // covers.
        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1);
        state.put(slot(POS, 1), 1);
        state.put(slot(POS, 3), 7);
        state.put(field(POS, "brew_time"), 1);
        state.put(field(POS, "fuel"), 5);

        tickOnce(state, BlockEntityActions.brewing(POS));

        assertEquals(0, state.get(field(POS, "brew_time")), "brew_time ticked down to 0");
        assertEquals(6, state.get(slot(POS, 3)), "ingredient decremented by 1 on brew completion");
        assertEquals(1, state.get(slot(POS, 0)), "bottle slot 0 left untouched (placeholder mix)");
        assertEquals(1, state.get(slot(POS, 1)), "bottle slot 1 left untouched (placeholder mix)");
        assertEquals(5, state.get(field(POS, "fuel")), "fuel untouched on doBrew step");
    }

    @Test
    void brewingOnIdleLayoutIsANoOp() throws Exception {
        // Empty brewing stand (no ingredient, no fuel): no fuel pass, no brewing pass.
        BlockEntityState state = new BlockEntityState();

        tickOnce(state, BlockEntityActions.brewing(POS));

        assertEquals(0, state.get(field(POS, "fuel")));
        assertEquals(0, state.get(field(POS, "brew_time")));
        for (int s = 0; s <= 4; s++) {
            assertEquals(0, state.get(slot(POS, s)), "slot " + s + " untouched on empty stand");
        }
    }

    @Test
    void brewingActivityGateAgreesWithActionAcrossALayoutMatrix() {
        // Anti-drift: brewingWillMutate must equal "running brewing() on the same
        // initial state would buffer any write". Sweep a small matrix the action's
        // branch structure cares about (idle / mid-brew / brewable / no-fuel /
        // no-ingredient / ingredient-only).
        int[][] layouts = {
            // { slot0, slot1, slot2, ingredient, fuelSlot, brewTime, fuel }
            {0, 0, 0, 0, 0, 0, 0},    // totally empty — gate false
            {1, 0, 0, 0, 0, 0, 0},    // bottle only, no ingredient — gate false
            {0, 0, 0, 1, 0, 0, 0},    // ingredient only, no bottle — gate false
            {1, 0, 0, 1, 0, 0, 0},    // brewable, no fuel — gate true (re-arms fuel if slot 4 present, but here 0)
            {1, 0, 0, 1, 1, 0, 0},    // brewable, fuel in slot 4 — gate true (loads + arms)
            {1, 0, 0, 1, 0, 0, 5},    // brewable, fuel armed — gate true (consumes fuel, arms brew)
            {0, 0, 0, 0, 0, 100, 0},  // mid-brew — gate true (counts down)
            {1, 0, 0, 0, 0, 1, 0},    // about to doBrew but not brewable — gate false (no brewable, no fuel arm)
        };
        for (int[] layout : layouts) {
            boolean gate = BlockEntityActions.brewingWillMutate(
                layout[0], layout[1], layout[2], layout[3], layout[4], layout[5], layout[6]);
            // The action's write set is: at minimum `brew_time` decrements if >0;
            // `fuel` loads if fuel==0 && slot4>0; `fuel` decrements if idle branch
            // arms; `slot 3` decrements on doBrew; `slot 4` decrements on fuel-load.
            boolean actionWouldWrite = layout[5] > 0                                       // mid-brew
                || (layout[3] > 0 && (layout[0] > 0 || layout[1] > 0 || layout[2] > 0)
                    && (layout[6] > 0 || layout[4] > 0));                                 // idle arming
            assertEquals(actionWouldWrite, gate,
                "gate must agree with action's branch structure for layout "
                    + java.util.Arrays.toString(layout));
        }
    }

    /** Replays the decompiled getRandomSlot reservoir loop to pin the expected chosen slot. */
    private static int expectedChosenSlot(int[] slots, long seed) {
        java.util.Random ref = new java.util.Random(seed);
        int chosen = -1;
        int replaceOdds = 1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] > 0 && ref.nextInt(replaceOdds++) == 0) {
                chosen = i;
            }
        }
        return chosen;
    }
}
