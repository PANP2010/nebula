package org.nebula.redstone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneStateSnapshotTest {

    private static final WorldPos POS1 = new WorldPos(0, 10, 64, 20);
    private static final WorldPos POS2 = new WorldPos(0, 11, 64, 20);
    private static final WorldPos POS3 = new WorldPos(0, 12, 64, 20);

    private RedstoneWorldState world;

    @BeforeEach
    void setUp() {
        world = new RedstoneWorldState();
        world.putPowerLevel(POS1, 10);
        world.putPowerLevel(POS2, 5);
    }

    @Test
    void emptySnapshotByDefault() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        assertTrue(snap.isEmpty());
        assertEquals(0, snap.pendingChangeCount());
    }

    @Test
    void readCapturesVersionAndValue() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        int power = snap.readPowerLevel(world, POS1);
        assertEquals(10, power);
    }

    @Test
    void readAbsentPositionReturnsMinusOne() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        int power = snap.readPowerLevel(world, POS3);
        assertEquals(-1, power);
    }

    @Test
    void writeBufferedNotAppliedImmediately() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);

        assertEquals(10, world.getPowerLevel(POS1), "World unchanged before commit");
        assertEquals(15, snap.getPendingPowerLevel(POS1));
        assertFalse(snap.isEmpty());
    }

    @Test
    void commitSucceedsWithNoConflict() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);

        RedstoneStateSnapshot.CommitResult result = snap.commit(world);
        assertTrue(result.success());
        assertEquals(15, world.getPowerLevel(POS1));
        assertTrue(snap.isCommitted());
    }

    @Test
    void commitFailsOnStaleRead() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);

        // Another task modifies POS1 between our read and commit
        world.putPowerLevel(POS1, 12);

        snap.setPowerLevel(POS1, 15);
        RedstoneStateSnapshot.CommitResult result = snap.commit(world);

        assertFalse(result.success());
        assertTrue(result.failedPositions().containsKey(POS1));
        assertEquals(12, world.getPowerLevel(POS1), "World retains the concurrent write");
    }

    @Test
    void commitMultiplePositionsPartialFailure() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.readPowerLevel(world, POS2);

        // Conflict only on POS2
        world.putPowerLevel(POS2, 99);

        snap.setPowerLevel(POS1, 15);
        snap.setPowerLevel(POS2, 7);

        RedstoneStateSnapshot.CommitResult result = snap.commit(world);
        assertFalse(result.success());
        assertTrue(result.failedPositions().containsKey(POS2));
        assertFalse(result.failedPositions().containsKey(POS1));
        // POS1 committed successfully
        assertEquals(15, world.getPowerLevel(POS1));
        // POS2 failed
        assertEquals(99, world.getPowerLevel(POS2));
    }

    @Test
    void commitInternalState() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 10);
        snap.setInternalState(POS1, "delay_counter", 3);

        RedstoneStateSnapshot.CommitResult result = snap.commit(world);
        assertTrue(result.success());
        assertEquals(3, world.getInternalState(POS1, "delay_counter"));
    }

    @Test
    void commitToAbsentPosition() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS3); // absent, version=0
        snap.setPowerLevel(POS3, 8);

        RedstoneStateSnapshot.CommitResult result = snap.commit(world);
        assertTrue(result.success());
        assertEquals(8, world.getPowerLevel(POS3));
    }

    @Test
    void discardDoesNotApplyWrites() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);

        snap.discard();
        assertEquals(10, world.getPowerLevel(POS1));
        assertTrue(snap.isCommitted());
    }

    @Test
    void cannotWriteAfterCommit() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);
        snap.commit(world);

        assertThrows(IllegalStateException.class, () -> snap.setPowerLevel(POS1, 20));
    }

    @Test
    void cannotCommitTwice() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);
        snap.commit(world);

        assertThrows(IllegalStateException.class, () -> snap.commit(world));
    }

    @Test
    void mergeFromCombinesDisjointSnapshots() {
        RedstoneStateSnapshot a = new RedstoneStateSnapshot();
        a.readPowerLevel(world, POS1);
        a.setPowerLevel(POS1, 15);

        RedstoneStateSnapshot b = new RedstoneStateSnapshot();
        b.readPowerLevel(world, POS2);
        b.setPowerLevel(POS2, 7);

        a.mergeFrom(b);
        assertEquals(2, a.pendingChangeCount());

        RedstoneStateSnapshot.CommitResult result = a.commit(world);
        assertTrue(result.success());
        assertEquals(15, world.getPowerLevel(POS1));
        assertEquals(7, world.getPowerLevel(POS2));
    }

    @Test
    void mergeFromLastWriterWins() {
        RedstoneStateSnapshot a = new RedstoneStateSnapshot();
        a.readPowerLevel(world, POS1);
        a.setPowerLevel(POS1, 10);

        RedstoneStateSnapshot b = new RedstoneStateSnapshot();
        b.readPowerLevel(world, POS1);
        b.setPowerLevel(POS1, 15);

        a.mergeFrom(b);
        assertEquals(15, a.getPendingPowerLevel(POS1));
    }

    @Test
    void changedPositionsTracksAllWrites() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.setPowerLevel(POS1, 10);
        snap.setInternalState(POS2, "key", "val");

        var changed = snap.changedPositions();
        assertEquals(2, changed.size());
        assertTrue(changed.contains(POS1));
        assertTrue(changed.contains(POS2));
    }

    @Test
    void clearResetsSnapshot() {
        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
        snap.readPowerLevel(world, POS1);
        snap.setPowerLevel(POS1, 15);
        snap.setInternalState(POS1, "x", 1);

        snap.clear();
        assertTrue(snap.isEmpty());
        assertFalse(snap.isCommitted());
        assertEquals(-1, snap.getPendingPowerLevel(POS1));
    }
}
