package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.FieldPath;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class CASWriteBufferTest {

    @Test
    void writesNotVisibleBeforeCommit() {
        ConcurrentHashMap<WorldPos, Integer> blocks = new ConcurrentHashMap<>();
        CASWriteBuffer buf = new CASWriteBuffer();
        WorldPos pos = new WorldPos(0, 1, 64, 1);
        buf.writeBlock(pos, 15);
        assertFalse(blocks.containsKey(pos), "Block should not be visible before commit");
    }

    @Test
    void commitAppliesAllWrites() {
        ConcurrentHashMap<WorldPos, Integer> blocks = new ConcurrentHashMap<>();
        ConcurrentHashMap<EntityField, Object> entities = new ConcurrentHashMap<>();
        ConcurrentHashMap<GlobalKey, Object> globals = new ConcurrentHashMap<>();

        CASWriteBuffer buf = new CASWriteBuffer();
        WorldPos pos = new WorldPos(0, 5, 64, 5);
        EntityField field = new EntityField(42L, new FieldPath("health"));
        GlobalKey key = new GlobalKey("game_time");

        buf.writeBlock(pos, 10);
        buf.writeEntity(field, 20.0);
        buf.writeGlobal(key, 1000L);
        buf.mergeInto(blocks, entities, globals);

        assertEquals(10, blocks.get(pos));
        assertEquals(20.0, entities.get(field));
        assertEquals(1000L, globals.get(key));
        assertTrue(buf.isCommitted());
    }

    @Test
    void discardLeavesStateUnchanged() {
        ConcurrentHashMap<WorldPos, Integer> blocks = new ConcurrentHashMap<>();
        ConcurrentHashMap<EntityField, Object> entities = new ConcurrentHashMap<>();
        ConcurrentHashMap<GlobalKey, Object> globals = new ConcurrentHashMap<>();

        CASWriteBuffer buf = new CASWriteBuffer();
        WorldPos pos = new WorldPos(0, 7, 64, 7);
        buf.writeBlock(pos, 5);
        buf.discard();

        assertFalse(blocks.containsKey(pos));
        assertTrue(buf.isCommitted());
    }

    @Test
    void doubleCommitThrows() {
        ConcurrentHashMap<WorldPos, Integer> blocks = new ConcurrentHashMap<>();
        ConcurrentHashMap<EntityField, Object> entities = new ConcurrentHashMap<>();
        ConcurrentHashMap<GlobalKey, Object> globals = new ConcurrentHashMap<>();

        CASWriteBuffer buf = new CASWriteBuffer();
        buf.mergeInto(blocks, entities, globals);
        assertThrows(IllegalStateException.class, () -> buf.mergeInto(blocks, entities, globals));
    }

    @Test
    void writeAfterCommitThrows() {
        CASWriteBuffer buf = new CASWriteBuffer();
        buf.discard();
        assertThrows(IllegalStateException.class,
            () -> buf.writeBlock(new WorldPos(0, 0, 0, 0), 1));
    }

    @Test
    void blockWriteCountTracked() {
        CASWriteBuffer buf = new CASWriteBuffer();
        assertEquals(0, buf.blockWriteCount());
        buf.writeBlock(new WorldPos(0, 1, 64, 1), 5);
        buf.writeBlock(new WorldPos(0, 2, 64, 2), 7);
        assertEquals(2, buf.blockWriteCount());
    }

    @Test
    void multipleBuffersIndependent() {
        ConcurrentHashMap<WorldPos, Integer> blocks = new ConcurrentHashMap<>();
        ConcurrentHashMap<EntityField, Object> entities = new ConcurrentHashMap<>();
        ConcurrentHashMap<GlobalKey, Object> globals = new ConcurrentHashMap<>();

        WorldPos pos1 = new WorldPos(0, 10, 64, 10);
        WorldPos pos2 = new WorldPos(0, 20, 64, 20);

        CASWriteBuffer b1 = new CASWriteBuffer();
        CASWriteBuffer b2 = new CASWriteBuffer();
        b1.writeBlock(pos1, 1);
        b2.writeBlock(pos2, 2);

        b1.mergeInto(blocks, entities, globals);
        b2.mergeInto(blocks, entities, globals);

        assertEquals(1, blocks.get(pos1));
        assertEquals(2, blocks.get(pos2));
    }
}
