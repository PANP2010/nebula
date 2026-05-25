package org.nebula.core.random;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.FieldPath;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteBufferTest {

    private static final WorldPos POS = new WorldPos(0, 10, 64, 20);
    private static final EntityField HEALTH = new EntityField(1L, new FieldPath("health"));
    private static final GlobalKey TIME = new GlobalKey("game_time");

    @Test
    void writesNotVisibleToStateBeforeCommit() {
        Map<WorldPos, Integer> blocks = new HashMap<>();
        WriteBuffer buf = new WriteBuffer();
        buf.writeBlock(POS, 15);
        assertFalse(blocks.containsKey(POS));
    }

    @Test
    void commitAppliesAllWrites() {
        Map<WorldPos, Integer> blocks = new HashMap<>();
        Map<EntityField, Object> entities = new HashMap<>();
        Map<GlobalKey, Object> globals = new HashMap<>();

        WriteBuffer buf = new WriteBuffer();
        buf.writeBlock(POS, 10);
        buf.writeEntity(HEALTH, 20.0);
        buf.writeGlobal(TIME, 1000L);
        buf.commit(blocks, entities, globals);

        assertEquals(10, blocks.get(POS));
        assertEquals(20.0, entities.get(HEALTH));
        assertEquals(1000L, globals.get(TIME));
        assertTrue(buf.isCommitted());
    }

    @Test
    void discardLeavesStateUnchanged() {
        Map<WorldPos, Integer> blocks = new HashMap<>();
        Map<EntityField, Object> entities = new HashMap<>();
        Map<GlobalKey, Object> globals = new HashMap<>();

        WriteBuffer buf = new WriteBuffer();
        buf.writeBlock(POS, 5);
        buf.discard();

        assertFalse(blocks.containsKey(POS));
        assertTrue(buf.isCommitted());
        assertTrue(buf.isEmpty());
    }

    @Test
    void writeAfterCommitThrows() {
        WriteBuffer buf = new WriteBuffer();
        buf.discard();
        assertThrows(IllegalStateException.class, () -> buf.writeBlock(POS, 1));
    }

    @Test
    void doubleCommitThrows() {
        Map<WorldPos, Integer> blocks = new java.util.HashMap<>();
        WriteBuffer buf = new WriteBuffer();
        buf.commit(blocks, new java.util.HashMap<>(), new java.util.HashMap<>());
        assertThrows(IllegalStateException.class,
            () -> buf.commit(blocks, new java.util.HashMap<>(), new java.util.HashMap<>()));
    }

    @Test
    void emptyBufferIsEmpty() {
        assertTrue(new WriteBuffer().isEmpty());
    }

    @Test
    void twoBuffersIndependent() {
        Map<WorldPos, Integer> blocks = new java.util.HashMap<>();
        WorldPos p1 = new WorldPos(0, 1, 64, 1);
        WorldPos p2 = new WorldPos(0, 2, 64, 2);

        WriteBuffer b1 = new WriteBuffer();
        WriteBuffer b2 = new WriteBuffer();
        b1.writeBlock(p1, 1);
        b2.writeBlock(p2, 2);

        b1.commit(blocks, new java.util.HashMap<>(), new java.util.HashMap<>());
        b2.commit(blocks, new java.util.HashMap<>(), new java.util.HashMap<>());

        assertEquals(1, blocks.get(p1));
        assertEquals(2, blocks.get(p2));
    }
}
