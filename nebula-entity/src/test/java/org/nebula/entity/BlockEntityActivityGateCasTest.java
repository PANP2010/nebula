package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link BlockEntityActivityGate#furnaceActive} — the CAS-store read that bridges
 * the pure {@link BlockEntityActions#furnaceWillMutate} predicate to the live seeder.
 *
 * <p>The predicate's own branch correctness is already differential-tested against the
 * real action in {@link BlockEntityActivityGateTest}. What THIS test guards is the piece
 * that class cannot: that {@code furnaceActive} reads the SAME canonical field paths the
 * furnace action and {@code NmsBlockEntityStateBridge} write — a mistyped path would read
 * a permanent 0 and silently mis-grade every furnace as idle (the field-path drift bug
 * class). So the anchor test drives the REAL furnace action through a context on the same
 * store and asserts the gate's verdict tracks whether that action actually moved CAS.
 */
class BlockEntityActivityGateCasTest {

    private static final WorldPos POS = new WorldPos(0, 4, 64, 9);

    private static BlockEntityField slot(int s) {
        return new BlockEntityField(POS, "inventory.slots[" + s + "]");
    }

    private static BlockEntityField field(String name) {
        return new BlockEntityField(POS, name);
    }

    private static BlockEntityState furnaceState(int input, int fuel, int output,
                                                 int cook, int fuelTime) {
        BlockEntityState state = new BlockEntityState();
        state.put(slot(0), input);
        state.put(slot(1), fuel);
        state.put(slot(2), output);
        state.put(field("cook_progress"), cook);
        state.put(field("fuel_time"), fuelTime);
        return state;
    }

    /** Runs the furnace action once against {@code state}, committing, and reports if CAS moved. */
    private static boolean furnaceTickMutated(BlockEntityState state) throws Exception {
        int in0 = state.get(slot(0));
        int fu0 = state.get(slot(1));
        int ou0 = state.get(slot(2));
        int ck0 = state.get(field("cook_progress"));
        int ft0 = state.get(field("fuel_time"));

        BlockEntitySnapshotState buffer = new BlockEntitySnapshotState();
        BlockEntityActions.furnace(POS).execute(new BlockEntityContext(state, buffer));
        buffer.commit(state);

        return in0 != state.get(slot(0))
            || fu0 != state.get(slot(1))
            || ou0 != state.get(slot(2))
            || ck0 != state.get(field("cook_progress"))
            || ft0 != state.get(field("fuel_time"));
    }

    @Test
    void furnaceActiveTracksTheRealActionAcrossTheStateMatrix() throws Exception {
        // Same boundaries the predicate's differential test sweeps, but driven through the
        // CAS store: if furnaceActive read a wrong field path it would see 0 there and its
        // verdict would diverge from the real action on some cell.
        int[] inputs = {0, 1, 2};
        int[] fuels = {0, 1};
        int[] outputs = {0, 63, 64};
        int[] cooks = {0, 1, 199};
        int[] fuelTimes = {0, 1};

        int cells = 0;
        for (int input : inputs) {
            for (int fuel : fuels) {
                for (int output : outputs) {
                    for (int cook : cooks) {
                        for (int fuelTime : fuelTimes) {
                            // Read the gate BEFORE running the action (the action mutates
                            // the store), then run the action on a fresh identical store.
                            boolean gate = BlockEntityActivityGate.furnaceActive(
                                furnaceState(input, fuel, output, cook, fuelTime), POS);
                            boolean actual = furnaceTickMutated(
                                furnaceState(input, fuel, output, cook, fuelTime));
                            assertEquals(actual, gate,
                                "furnaceActive disagreed with the action for "
                                    + "input=" + input + " fuel=" + fuel + " output=" + output
                                    + " cook=" + cook + " fuelTime=" + fuelTime);
                            cells++;
                        }
                    }
                }
            }
        }
        assertEquals(3 * 2 * 3 * 3 * 2, cells, "state matrix must be fully swept");
    }

    @Test
    void unsetStoreReadsAsFullyIdleFurnace() {
        // An empty store (no syncFromNms yet, or a cold empty furnace) reads all-zero and
        // must classify quiescent — never re-seed a furnace that has nothing to do.
        assertFalse(BlockEntityActivityGate.furnaceActive(new BlockEntityState(), POS),
            "an empty CAS store is a cold empty furnace — quiescent");
    }

    @Test
    void furnaceActiveFlagsABurningFurnaceReadFromCas() {
        // The exact case the InventoryMoveItemEvent seed path misses: mid-cook, burning,
        // no transfer event. Reads active straight off the store.
        assertTrue(BlockEntityActivityGate.furnaceActive(
                furnaceState(1, 0, 0, 50, 10), POS),
            "a burning cooking furnace read from CAS is active and must be re-seeded");
    }

    @Test
    void slotPathsAreReadIndependently() {
        // Guards against a gate that read the same slot thrice (a copy-paste path bug):
        // only slot 1 (fuel) is loaded, so the can-smelt branch must NOT fire on input,
        // and with no input the furnace is idle with nothing to decay — quiescent.
        BlockEntityState fuelOnly = new BlockEntityState();
        fuelOnly.put(slot(1), 5);
        assertFalse(BlockEntityActivityGate.furnaceActive(fuelOnly, POS),
            "fuel with no input and no leftover progress does nothing — quiescent");

        // And with input in slot 0 plus that fuel, it ignites — proving slot 0 and slot 1
        // are read from distinct paths, not aliased.
        BlockEntityState inputAndFuel = new BlockEntityState();
        inputAndFuel.put(slot(0), 3);
        inputAndFuel.put(slot(1), 5);
        assertTrue(BlockEntityActivityGate.furnaceActive(inputAndFuel, POS),
            "input plus fuel ignites — slots 0 and 1 must be distinct paths");
    }

    // ── Brewing gate (B8 C3 brewing guard slice) ─────────────────────────────────

    /**
     * Brewing analog of {@link #furnaceState(int, int, int, int, int)}. Slots 0..2 are
     * bottles, slot 3 the ingredient, slot 4 the blaze powder; {@code brew_time} and
     * {@code fuel} are the two timer fields. Same canonical field-path contract: a
     * mistyped path in {@code brewingActive} would read a permanent 0 and silently
     * grade every brewing stand idle.
     */
    private static BlockEntityState brewingState(int slot0, int slot1, int slot2,
                                                 int ingredient, int blaze,
                                                 int brewTime, int fuel) {
        BlockEntityState state = new BlockEntityState();
        state.put(slot(0), slot0);
        state.put(slot(1), slot1);
        state.put(slot(2), slot2);
        state.put(slot(3), ingredient);
        state.put(slot(4), blaze);
        state.put(field("brew_time"), brewTime);
        state.put(field("fuel"), fuel);
        return state;
    }

    @Test
    void brewingActiveReadsFromCanonicalFields() {
        // The branch coverage that matters for the live seed loop:
        // - countdown branch (brew_time > 0) must fire whenever brew_time > 0.
        // - arm-and-load branch (no brew_time, ingredient+bottle present, fuel or
        //   blaze-powder available) must fire.
        // - the truly idle layout (no brew_time, no ingredient, no fuel) must NOT fire.
        // If brewingActive read an off-by-one field path, the first case would silently
        // miss and the live seeder would never re-seed a mid-brew stand.
        assertTrue(BlockEntityActivityGate.brewingActive(
                brewingState(1, 0, 0, 1, 0, 50, 10), POS),
            "brew_time > 0 is active regardless of layout");
        assertFalse(BlockEntityActivityGate.brewingActive(
                brewingState(0, 0, 0, 0, 0, 0, 0), POS),
            "fully empty brewing stand is idle");
        assertTrue(BlockEntityActivityGate.brewingActive(
                brewingState(1, 0, 0, 1, 1, 0, 0), POS),
            "ingredient+bottle+blaze-powder with no fuel-time is armable — active");
        assertTrue(BlockEntityActivityGate.brewingActive(
                brewingState(1, 0, 0, 1, 0, 0, 5), POS),
            "ingredient+bottle with non-zero fuel is armable — active");
        assertFalse(BlockEntityActivityGate.brewingActive(
                brewingState(1, 0, 0, 0, 1, 0, 5), POS),
            "no ingredient is never armable — idle");
        assertFalse(BlockEntityActivityGate.brewingActive(
                brewingState(0, 0, 0, 1, 1, 0, 5), POS),
            "no bottle slot is never armable — idle");
    }
}
