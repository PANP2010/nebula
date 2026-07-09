package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link BlockEntityClassifier} and {@link BlockEntitySnapshot#forType} — the pure
 * core of the block-entity seed path (B8 C3). Regression guard for the ce6abf2 bring-up
 * bug where the live listener stamped EVERY transfer endpoint as {@code HOPPER}, so a
 * chest destination got a fictional hopper task and a dropper/dispenser/furnace got the
 * wrong RW-set.
 */
class BlockEntityClassifierTest {

    private static final WorldPos POS = new WorldPos(0, 10, 64, -3);

    @Test
    void classifiesTickingBlockEntities() {
        assertEquals(BlockEntityTaskType.HOPPER, BlockEntityClassifier.classify("HOPPER"));
        assertEquals(BlockEntityTaskType.DROPPER, BlockEntityClassifier.classify("DROPPER"));
        assertEquals(BlockEntityTaskType.DISPENSER, BlockEntityClassifier.classify("DISPENSER"));
        assertEquals(BlockEntityTaskType.BREWING_STAND, BlockEntityClassifier.classify("BREWING_STAND"));
    }

    @Test
    void allThreeFurnaceVariantsMapToFurnace() {
        assertEquals(BlockEntityTaskType.FURNACE, BlockEntityClassifier.classify("FURNACE"));
        assertEquals(BlockEntityTaskType.FURNACE, BlockEntityClassifier.classify("BLAST_FURNACE"));
        assertEquals(BlockEntityTaskType.FURNACE, BlockEntityClassifier.classify("SMOKER"));
    }

    @Test
    void nonTickingTargetsClassifyNull() {
        // A chest/barrel/shulker is a transfer target, not an autonomously-ticking
        // task — the exact endpoint the old code mis-stamped as a hopper.
        assertNull(BlockEntityClassifier.classify("CHEST"));
        assertNull(BlockEntityClassifier.classify("TRAPPED_CHEST"));
        assertNull(BlockEntityClassifier.classify("BARREL"));
        assertNull(BlockEntityClassifier.classify("SHULKER_BOX"));
    }

    @Test
    void toleratesNamespaceCaseAndWhitespace() {
        assertEquals(BlockEntityTaskType.HOPPER, BlockEntityClassifier.classify("minecraft:hopper"));
        assertEquals(BlockEntityTaskType.FURNACE, BlockEntityClassifier.classify("  blast_furnace  "));
        assertNull(BlockEntityClassifier.classify(null));
        assertNull(BlockEntityClassifier.classify(""));
    }

    @Test
    void forTypeAppliesFacingOnlyToDirectionalTypes() {
        // Hopper: facing carried into outputPos.
        BlockEntitySnapshot hopper = BlockEntitySnapshot.forType(
            BlockEntityTaskType.HOPPER, POS, 0, -1, 0);
        assertEquals(BlockEntityTaskType.HOPPER, hopper.type());
        assertEquals(5, hopper.slotCount());
        assertEquals(new WorldPos(0, 10, 63, -3), hopper.outputPos());

        // Dropper/dispenser: 9 slots, facing carried.
        assertEquals(9, BlockEntitySnapshot.forType(
            BlockEntityTaskType.DROPPER, POS, 1, 0, 0).slotCount());
        assertEquals(9, BlockEntitySnapshot.forType(
            BlockEntityTaskType.DISPENSER, POS, 1, 0, 0).slotCount());
    }

    @Test
    void forTypeIgnoresFacingForFurnaceAndBrewingStand() {
        // Furnace/brewing stand have no directional output — facing must not leak into
        // outputPos even if the caller passes a nonzero facing.
        BlockEntitySnapshot furnace = BlockEntitySnapshot.forType(
            BlockEntityTaskType.FURNACE, POS, 5, 5, 5);
        assertEquals(BlockEntityTaskType.FURNACE, furnace.type());
        assertEquals(3, furnace.slotCount());
        assertEquals(POS, furnace.outputPos());

        BlockEntitySnapshot brewer = BlockEntitySnapshot.forType(
            BlockEntityTaskType.BREWING_STAND, POS, 5, 5, 5);
        assertEquals(5, brewer.slotCount());
        assertEquals(POS, brewer.outputPos());
    }

    @Test
    void forTypeRoundTripsTaskIdGrammar() {
        // The forType snapshot must stamp the same 2-token TYPE@dim:x,y,z grammar the
        // region-dispatch decoder (POSITION_OF) parses.
        assertEquals("BLOCK_ENTITY_FURNACE@0:10,64,-3",
            BlockEntitySnapshot.forType(BlockEntityTaskType.FURNACE, POS, 0, 0, 0).taskId());
    }
}
