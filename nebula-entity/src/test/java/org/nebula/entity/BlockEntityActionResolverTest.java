package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link BlockEntityActionResolver} — the snapshot→{@link BlockEntityAction}
 * seam that swaps the inert block-entity resolver for real item math (B8 C3).
 *
 * <p>These are correctness tests, not just non-null switch coverage: the hopper and
 * furnace cases resolve the action and then <em>execute</em> it through a real
 * {@link BlockEntityContext} against a {@link BlockEntityState}, asserting the CAS
 * state actually mutates the way {@link BlockEntityActions} specifies. That is what
 * makes this a provable prerequisite rather than a plausible-looking wiring.
 */
class BlockEntityActionResolverTest {

    private static final WorldPos POS = new WorldPos(0, 4, 64, 9);

    /** Runs a resolved action once against the given state via a fresh snapshot buffer, committing writes. */
    private static void tickOnce(BlockEntityState state, BlockEntityAction action) throws Exception {
        BlockEntitySnapshotState buffer = new BlockEntitySnapshotState();
        action.execute(new BlockEntityContext(state, buffer));
        buffer.commit(state);
    }

    /**
     * Same as {@link #tickOnce} but supplies a deterministic RNG stream, as the live
     * runner does for an RNG-declaring dropper/dispenser task — so {@code ctx.random()}
     * returns a stream instead of throwing.
     */
    private static void tickOnceWithRng(BlockEntityState state, BlockEntityAction action) throws Exception {
        BlockEntitySnapshotState buffer = new BlockEntitySnapshotState();
        action.execute(new BlockEntityContext(state, buffer, null,
            new org.nebula.core.random.DeterministicRandom(0xB10CE117L)));
        buffer.commit(state);
    }

    private static BlockEntityField slot(WorldPos pos, int s) {
        return new BlockEntityField(pos, "inventory.slots[" + s + "]");
    }

    @Test
    void hopperResolvesToTransferMath() throws Exception {
        // Hopper facing down (output one below), an item waiting above.
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.hopper(POS, 0, -1, 0);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);
        assertNotNull(action, "hopper must resolve to a live action");

        BlockEntityState state = new BlockEntityState();
        WorldPos above = snapshot.abovePos();
        WorldPos output = snapshot.outputPos();
        state.put(slot(above, 0), 3); // three items sitting on top of the hopper

        tickOnce(state, action);

        // One item pulled from above into self slot 0, then that same item pushed on
        // to the output below in the same tick — so self slot 0 nets back to 0 and the
        // item lands in the output. Cooldown armed because a move happened.
        assertEquals(2, state.get(slot(above, 0)), "one item pulled from above");
        assertEquals(0, state.get(slot(POS, 0)), "pulled item pushed on to output, self nets to 0");
        assertEquals(1, state.get(slot(output, 0)), "item landed in the output below");
        assertEquals(BlockEntityActions.HOPPER_COOLDOWN,
            state.get(new BlockEntityField(POS, "transfer_cooldown")),
            "transfer cooldown armed after a move");
    }

    @Test
    void hopperOnCooldownOnlyDecrements() throws Exception {
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.hopper(POS, 0, -1, 0);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);

        BlockEntityState state = new BlockEntityState();
        state.put(new BlockEntityField(POS, "transfer_cooldown"), 5);
        state.put(slot(snapshot.abovePos(), 0), 3);

        tickOnce(state, action);

        // Still on cooldown: no transfer, cooldown ticks down by one.
        assertEquals(4, state.get(new BlockEntityField(POS, "transfer_cooldown")));
        assertEquals(3, state.get(slot(snapshot.abovePos(), 0)), "no pull while on cooldown");
    }

    @Test
    void furnaceResolvesToSmeltMath() throws Exception {
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.furnace(POS);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);
        assertNotNull(action, "furnace must resolve to a live action");

        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 1); // one input
        state.put(slot(POS, 1), 1); // one fuel
        // One cook-tick short of completing, already burning so no fuel is consumed.
        state.put(new BlockEntityField(POS, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(POS, "fuel_time"), 10);

        tickOnce(state, action);

        // Smelt completes: input consumed, output produced, progress reset.
        assertEquals(0, state.get(slot(POS, 0)), "input consumed on smelt completion");
        assertEquals(1, state.get(slot(POS, 2)), "output produced");
        assertEquals(0, state.get(new BlockEntityField(POS, "cook_progress")), "progress reset");
    }

    @Test
    void dropperResolvesToEjectMath() throws Exception {
        // The resolver now flips DROPPER to the vanilla getRandomSlot reservoir draw +
        // one-item self-slot eject (BlockEntityActions.dropper). The runner supplies the
        // WORLD_RANDOM stream live (the RW-set declares RandomUsage); here we feed a
        // deterministic stream directly to prove the resolved action ejects exactly one
        // item from a non-empty slot.
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.dropper(POS, 0, -1, 0);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);
        assertNotNull(action, "dropper must resolve to a live eject action");

        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 3), 5); // five items in one slot, the rest empty

        tickOnceWithRng(state, action);

        // getRandomSlot over a single non-empty slot always picks it; one item ejected.
        assertEquals(4, state.get(slot(POS, 3)), "one item ejected from the only loaded slot");
    }

    @Test
    void dispenserResolvesToEjectMath() throws Exception {
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.dispenser(POS, 0, -1, 0);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);
        assertNotNull(action, "dispenser must resolve to a live eject action");

        BlockEntityState state = new BlockEntityState();
        state.put(slot(POS, 0), 2);

        tickOnceWithRng(state, action);

        assertEquals(1, state.get(slot(POS, 0)), "one item ejected from the only loaded slot");
    }

    @Test
    void emptyDispenserEjectsNothing() throws Exception {
        // getRandomSlot returns -1 over an all-empty container: no-op, matching vanilla.
        BlockEntitySnapshot snapshot = BlockEntitySnapshot.dropper(POS, 0, -1, 0);
        BlockEntityAction action = BlockEntityActionResolver.resolve(snapshot);

        BlockEntityState state = new BlockEntityState();
        tickOnceWithRng(state, action); // must not throw and must mutate nothing

        for (int s = 0; s < 9; s++) {
            assertEquals(0, state.get(slot(POS, s)), "empty container: nothing ejected from slot " + s);
        }
    }

    @Test
    void brewingStandResolvesToBrewingAction() {
        // BREWING_STAND now has real action math (B8 C3 brewing slice) —
        // BlockEntityActions.brewing self-ticks the integer-only brew state machine.
        BlockEntityAction action = BlockEntityActionResolver.resolve(
            BlockEntitySnapshot.brewingStand(POS));
        assertNotNull(action, "BREWING_STAND must resolve to the brewing action");
    }

    @Test
    void nullSnapshotResolvesNull() {
        assertNull(BlockEntityActionResolver.resolve(null));
    }
}
