package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential test for {@link BlockEntityActions#furnaceWillMutate} — the pure activity
 * gate the live cook-tick furnace seeder (B8 C3, named next epic) will lean on.
 *
 * <p>An autonomously-smelting furnace fires no {@code InventoryMoveItemEvent}, so the
 * live seed path never re-seeds it once it starts cooking; the seeder therefore needs a
 * predicate answering "would this furnace's next tick change any CAS field?" to know
 * which furnaces are still active (must be re-seeded) and which are quiescent (safe for
 * the {@code BE-SETTLED} grader to treat as at-rest). This test does not trust the
 * predicate's hand-derived branches on their own: for every point of a state matrix it
 * runs the REAL {@link BlockEntityActions#furnace} action once through a
 * {@link BlockEntityContext} and asserts the predicate agrees with whether the committed
 * CAS state actually moved. If the action's branch structure ever drifts from the gate
 * (the under-declaration bug class), some matrix cell disagrees and this fails — the
 * anti-drift guarantee the gate's javadoc promises.
 */
class BlockEntityActivityGateTest {

    private static final WorldPos POS = new WorldPos(0, 4, 64, 9);

    private static BlockEntityField slot(int s) {
        return new BlockEntityField(POS, "inventory.slots[" + s + "]");
    }

    private static BlockEntityField field(String name) {
        return new BlockEntityField(POS, name);
    }

    /** Builds a fresh furnace state with the given five inputs. */
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

    /** Runs the furnace action once, committing writes, and reports whether any field moved. */
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
    void gateAgreesWithTheActionAcrossTheStateMatrix() throws Exception {
        // Sweep the boundaries every furnace branch pivots on: input presence, fuel
        // presence, output emptiness/fullness (63 vs 64), cook progress at floor / mid /
        // just-below-completion, and burn time zero vs. burning.
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
                            boolean predicted = BlockEntityActions.furnaceWillMutate(
                                input, fuel, output, cook, fuelTime);
                            boolean actual = furnaceTickMutated(
                                furnaceState(input, fuel, output, cook, fuelTime));
                            assertEquals(actual, predicted,
                                "furnaceWillMutate disagreed with the action for "
                                    + "input=" + input + " fuel=" + fuel + " output=" + output
                                    + " cook=" + cook + " fuelTime=" + fuelTime);
                            cells++;
                        }
                    }
                }
            }
        }
        // Guard against a silently-empty sweep (all loops skipped would pass vacuously).
        assertEquals(3 * 2 * 3 * 3 * 2, cells, "state matrix must be fully swept");
    }

    @Test
    void gateFlagsActivelyBurningFurnace() {
        // The exact case the live path misses today: burning, mid-cook, no move event.
        assertEquals(true, BlockEntityActions.furnaceWillMutate(1, 0, 0, 50, 10),
            "a burning, cooking furnace is active and must be re-seeded");
    }

    @Test
    void gateReportsFullyIdleFurnaceQuiescent() {
        // Empty, cold, floored: nothing to do — the grader may treat this as settled.
        assertEquals(false, BlockEntityActions.furnaceWillMutate(0, 0, 0, 0, 0),
            "an empty cold furnace mutates nothing and is quiescent");
    }

    @Test
    void gateReportsColdFurnaceWithInputButNoFuelQuiescent() {
        // Input waiting but no fuel and no leftover progress: it cannot start, so it is
        // at rest — NOT active. (With leftover cook>0 it would decay, hence active.)
        assertEquals(false, BlockEntityActions.furnaceWillMutate(5, 0, 0, 0, 0),
            "input with no fuel and no leftover progress cannot start — quiescent");
        assertEquals(true, BlockEntityActions.furnaceWillMutate(5, 0, 0, 3, 0),
            "leftover cook progress still decays — active until it floors");
    }
}
