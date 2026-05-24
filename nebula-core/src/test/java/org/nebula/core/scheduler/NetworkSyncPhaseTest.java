package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSyncPhaseTest {

    @Test
    void collectsDirtyBlocks() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        sync.markBlockDirty(new WorldPos(0, 10, 64, 10));
        sync.markBlockDirty(new WorldPos(0, 20, 64, 20));

        assertEquals(2, sync.dirtyBlocks().size());
    }

    @Test
    void playerSeesOnlyNearbyChanges() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        WorldPos near = new WorldPos(0, 5, 64, 5);
        WorldPos far = new WorldPos(0, 500, 64, 500);
        sync.markBlockDirty(near);
        sync.markBlockDirty(far);

        WorldPos playerPos = new WorldPos(0, 0, 64, 0);
        NetworkSyncPhase.PlayerUpdateSet updates = sync.computeForPlayer(playerPos, 160, 0);

        assertTrue(updates.blocks().contains(near));
        assertFalse(updates.blocks().contains(far));
    }

    @Test
    void playerInDifferentDimensionSeesNothing() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        sync.markBlockDirty(new WorldPos(0, 5, 64, 5));

        WorldPos playerPos = new WorldPos(-1, 5, 64, 5);
        NetworkSyncPhase.PlayerUpdateSet updates = sync.computeForPlayer(playerPos, 160, -1);

        assertTrue(updates.blocks().isEmpty());
    }

    @Test
    void resetClearsState() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        sync.markBlockDirty(new WorldPos(0, 10, 64, 10));
        sync.markEntityDirty(123L);
        sync.reset();

        assertTrue(sync.dirtyBlocks().isEmpty());
        assertTrue(sync.dirtyEntities().isEmpty());
    }

    @Test
    void entityDirtyTracking() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        sync.markEntityDirty(100L);
        sync.markEntityDirty(200L);
        sync.markEntityDirty(100L); // duplicate

        assertEquals(2, sync.dirtyEntities().size());
    }

    @Test
    void emptyUpdateSet() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        WorldPos playerPos = new WorldPos(0, 0, 64, 0);
        NetworkSyncPhase.PlayerUpdateSet updates = sync.computeForPlayer(playerPos, 160, 0);

        assertTrue(updates.isEmpty());
        assertEquals(0, updates.totalChanges());
    }

    @Test
    void multiplePlayersGetIndependentSets() {
        NetworkSyncPhase sync = new NetworkSyncPhase();
        WorldPos blockNearA = new WorldPos(0, 10, 64, 10);
        WorldPos blockNearB = new WorldPos(0, 900, 64, 900);
        sync.markBlockDirty(blockNearA);
        sync.markBlockDirty(blockNearB);

        WorldPos playerA = new WorldPos(0, 0, 64, 0);
        WorldPos playerB = new WorldPos(0, 890, 64, 890);

        var updatesA = sync.computeForPlayer(playerA, 160, 0);
        var updatesB = sync.computeForPlayer(playerB, 160, 0);

        assertTrue(updatesA.blocks().contains(blockNearA));
        assertFalse(updatesA.blocks().contains(blockNearB));

        assertFalse(updatesB.blocks().contains(blockNearA));
        assertTrue(updatesB.blocks().contains(blockNearB));
    }
}
