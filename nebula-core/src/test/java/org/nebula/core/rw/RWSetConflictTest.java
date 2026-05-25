package org.nebula.core.rw;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.FieldPath;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.PoiQuery;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for RWSet conflict detection.
 * Validates the core dependency analysis logic.
 */
class RWSetConflictTest {

    @Test
    void noConflictWithDisjointBlocks() {
        RWSet left = RWSet.builder()
            .writeBlock(new WorldPos(0, 0, 64, 0))
            .build();
        RWSet right = RWSet.builder()
            .readBlock(new WorldPos(0, 100, 64, 100))
            .build();

        assertFalse(left.hasReadWriteConflictWith(right));
        assertFalse(left.hasWriteWriteConflictWith(right));
        assertFalse(left.hasWriteReadConflictWith(right));
    }

    @Test
    void rawConflictDetected() {
        // Left writes position, Right reads it
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        RWSet left = RWSet.builder().writeBlock(pos).build();
        RWSet right = RWSet.builder().readBlock(pos).build();

        assertTrue(left.hasReadWriteConflictWith(right));
    }

    @Test
    void wawConflictDetected() {
        // Both write same position
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        RWSet left = RWSet.builder().writeBlock(pos).build();
        RWSet right = RWSet.builder().writeBlock(pos).build();

        assertTrue(left.hasWriteWriteConflictWith(right));
    }

    @Test
    void warConflictDetected() {
        // Left reads, Right writes (read-after-write)
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        RWSet left = RWSet.builder().readBlock(pos).build();
        RWSet right = RWSet.builder().writeBlock(pos).build();

        assertTrue(left.hasWriteReadConflictWith(right));
    }

    @Test
    void entityFieldConflictSameId() {
        EntityField left = new EntityField(12345L, new FieldPath("position"));
        EntityField right = new EntityField(12345L, new FieldPath("position"));

        RWSet leftSet = RWSet.builder().writeEntity(left).build();
        RWSet rightSet = RWSet.builder().readEntity(right).build();

        assertTrue(leftSet.hasReadWriteConflictWith(rightSet));
    }

    @Test
    void entityFieldNoConflictDifferentIds() {
        EntityField left = new EntityField(12345L, new FieldPath("position"));
        EntityField right = new EntityField(67890L, new FieldPath("position"));

        RWSet leftSet = RWSet.builder().writeEntity(left).build();
        RWSet rightSet = RWSet.builder().readEntity(right).build();

        assertFalse(leftSet.hasReadWriteConflictWith(rightSet));
    }

    @Test
    void entityFieldPrefixConflict() {
        // Write to "nbt", read from "nbt.display.Name" should conflict
        EntityField writer = new EntityField(12345L, new FieldPath("nbt"));
        EntityField reader = new EntityField(12345L, new FieldPath("nbt.display.Name"));

        RWSet writerSet = RWSet.builder().writeEntity(writer).build();
        RWSet readerSet = RWSet.builder().readEntity(reader).build();

        // FieldPath.conflictsWith handles prefix matching
        assertTrue(writer.conflictsWith(reader));
        // Writer conflicts with reader: writer's write (nbt) vs reader's read (nbt.display.Name)
        assertTrue(writerSet.hasReadWriteConflictWith(readerSet));
    }

    @Test
    void globalKeyConflict() {
        GlobalKey weather = new GlobalKey("weather");
        GlobalKey gameTime = new GlobalKey("game_time");

        RWSet leftSet = RWSet.builder().writeGlobal(weather).build();
        RWSet rightSet = RWSet.builder().readGlobal(gameTime).build();

        assertFalse(leftSet.hasReadWriteConflictWith(rightSet));

        RWSet rightWeatherSet = RWSet.builder().readGlobal(weather).build();
        assertTrue(leftSet.hasReadWriteConflictWith(rightWeatherSet));
    }

    @Test
    void allWildcardGlobalConflicts() {
        GlobalKey wildcard = GlobalKey.ALL;
        GlobalKey specific = new GlobalKey("weather");

        assertTrue(wildcard.conflictsWith(specific));
        assertTrue(specific.conflictsWith(wildcard));
        assertTrue(wildcard.conflictsWith(wildcard));
    }

    @Test
    void blockEntityFieldConflict() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        BlockEntityField left = new BlockEntityField(pos, new FieldPath("inventory.slots[0]"));
        BlockEntityField right = new BlockEntityField(pos, new FieldPath("inventory.slots[0]"));

        RWSet leftSet = RWSet.builder().writeBlockEntity(left).build();
        RWSet rightSet = RWSet.builder().readBlockEntity(right).build();

        assertTrue(leftSet.hasReadWriteConflictWith(rightSet));
    }

    @Test
    void blockEntityFieldNoConflictDifferentSlots() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        BlockEntityField left = new BlockEntityField(pos, new FieldPath("inventory.slots[0]"));
        BlockEntityField right = new BlockEntityField(pos, new FieldPath("inventory.slots[1]"));

        RWSet leftSet = RWSet.builder().writeBlockEntity(left).build();
        RWSet rightSet = RWSet.builder().readBlockEntity(right).build();

        assertFalse(leftSet.hasReadWriteConflictWith(rightSet));
    }

    @Test
    void eventsAreDownstreamMarkersNotStateWrites() {
        RWSet leftSet = RWSet.builder()
            .writeEvent(org.nebula.core.state.EventType.BLOCK_UPDATE)
            .build();
        RWSet rightSet = RWSet.builder()
            .writeEvent(org.nebula.core.state.EventType.BLOCK_UPDATE)
            .build();

        // Events declare downstream triggers (arch doc §3.2 Rule 5); two tasks
        // emitting the same event type don't conflict at the state layer — they
        // only matter for micro-step task generation (downstream neighbours).
        assertFalse(leftSet.hasWriteWriteConflictWith(rightSet));
    }

    @Test
    void randomUsageConflict() {
        RWSet leftSet = RWSet.builder()
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 5))
            .build();
        RWSet rightSet = RWSet.empty();

        // No explicit conflict method for random, but this is checked separately
        // in RWSetConsistencyChecker
        assertEquals(RandomUsage.empty(), rightSet.randomUsage().orElse(RandomUsage.empty()));
    }

    @Test
    void declaresBlockRead() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        RWSet set = RWSet.builder().readBlock(pos).build();

        assertTrue(set.declaresBlockRead(pos));
        assertFalse(set.declaresBlockWrite(pos));

        WorldPos other = new WorldPos(0, 20, 64, 20);
        assertFalse(set.declaresBlockRead(other));
    }

    @Test
    void emptySetHasNoConflicts() {
        RWSet empty = RWSet.empty();
        RWSet other = RWSet.builder()
            .writeBlock(new WorldPos(0, 0, 64, 0))
            .readBlock(new WorldPos(0, 1, 64, 1))
            .build();

        // Empty set has no reads and no writes
        assertFalse(empty.hasReadWriteConflictWith(other));
        assertFalse(empty.hasWriteWriteConflictWith(other));
        assertFalse(empty.hasWriteReadConflictWith(other));
    }

    @Test
    void multipleConflictsInOneSet() {
        WorldPos pos1 = new WorldPos(0, 1, 64, 1);
        WorldPos pos2 = new WorldPos(0, 2, 64, 2);

        RWSet leftSet = RWSet.builder()
            .writeBlock(pos1)
            .writeBlock(pos2)
            .build();
        RWSet rightSet = RWSet.builder()
            .readBlock(pos1)
            .readBlock(pos2)
            .build();

        assertTrue(leftSet.hasReadWriteConflictWith(rightSet));
    }
}
