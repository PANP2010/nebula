package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.FieldPath;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ThreadLocalAccessTrace.
 * Validates runtime access tracking during task execution.
 */
class ThreadLocalAccessTraceTest {

    @Test
    void traceAndSnapshot() {
        ThreadLocalAccessTrace.reset();

        WorldPos pos = new WorldPos(0, 10, 64, 10);
        ThreadLocalAccessTrace.traceBlockRead(pos);
        ThreadLocalAccessTrace.traceBlockWrite(pos);

        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertTrue(snapshot.readBlocks().contains(pos));
        assertTrue(snapshot.writtenBlocks().contains(pos));
        assertTrue(snapshot.readBlocks().size() == 1);
        assertTrue(snapshot.writtenBlocks().size() == 1);
    }

    @Test
    void traceEntityFields() {
        ThreadLocalAccessTrace.reset();

        EntityField position = new EntityField(12345L, new FieldPath("position"));
        EntityField health = new EntityField(12345L, new FieldPath("health"));

        ThreadLocalAccessTrace.traceEntityRead(position);
        ThreadLocalAccessTrace.traceEntityWrite(health);

        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertTrue(snapshot.readEntityFields().contains(position));
        assertTrue(snapshot.writtenEntityFields().contains(health));
    }

    @Test
    void traceBlockEntities() {
        ThreadLocalAccessTrace.reset();

        WorldPos pos = new WorldPos(0, 10, 64, 10);
        BlockEntityField inv0 = new BlockEntityField(pos, new FieldPath("inventory.slots[0]"));
        BlockEntityField inv1 = new BlockEntityField(pos, new FieldPath("inventory.slots[1]"));

        ThreadLocalAccessTrace.traceBlockEntityRead(inv0);
        ThreadLocalAccessTrace.traceBlockEntityWrite(inv1);

        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertTrue(snapshot.readBlockEntities().contains(inv0));
        assertTrue(snapshot.writtenBlockEntities().contains(inv1));
    }

    @Test
    void traceGlobalKeys() {
        ThreadLocalAccessTrace.reset();

        GlobalKey weather = new GlobalKey("weather");
        GlobalKey gameTime = new GlobalKey("game_time");

        ThreadLocalAccessTrace.traceGlobalRead(weather);
        ThreadLocalAccessTrace.traceGlobalWrite(gameTime);

        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertTrue(snapshot.readGlobalKeys().contains(weather));
        assertTrue(snapshot.writtenGlobalKeys().contains(gameTime));
    }

    @Test
    void traceRandomCalls() {
        ThreadLocalAccessTrace.reset();

        ThreadLocalAccessTrace.traceRandomCall(RandomInstance.ENTITY_RANDOM);
        ThreadLocalAccessTrace.traceRandomCall(RandomInstance.ENTITY_RANDOM);
        ThreadLocalAccessTrace.traceRandomCall(RandomInstance.WORLD_RANDOM);

        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertEquals(2, snapshot.randomCalls().get(RandomInstance.ENTITY_RANDOM));
        assertEquals(1, snapshot.randomCalls().get(RandomInstance.WORLD_RANDOM));
    }

    @Test
    void resetClearsAllTraces() {
        ThreadLocalAccessTrace.traceBlockRead(new WorldPos(0, 0, 64, 0));
        ThreadLocalAccessTrace.traceEntityRead(new EntityField(1L, new FieldPath("position")));

        ThreadLocalAccessTrace.reset();

        ActualAccessTrace afterReset = ThreadLocalAccessTrace.snapshot();
        assertTrue(afterReset.readBlocks().isEmpty());
        assertTrue(afterReset.readEntityFields().isEmpty());
    }

    @Test
    void snapshotIsImmutable() {
        ThreadLocalAccessTrace.reset();
        ActualAccessTrace snapshot1 = ThreadLocalAccessTrace.snapshot();

        // Add more after snapshot
        ThreadLocalAccessTrace.traceBlockRead(new WorldPos(0, 1, 64, 1));

        ActualAccessTrace snapshot2 = ThreadLocalAccessTrace.snapshot();

        // Snapshot should be independent copies
        assertEquals(0, snapshot1.readBlocks().size());
        assertEquals(1, snapshot2.readBlocks().size());
    }

    @Test
    void snapshotReturnsUnmodifiableCollections() {
        ThreadLocalAccessTrace.reset();
        ActualAccessTrace snapshot = ThreadLocalAccessTrace.snapshot();

        assertThrows(UnsupportedOperationException.class, () ->
            snapshot.readBlocks().add(new WorldPos(0, 0, 64, 0)));
    }
}
