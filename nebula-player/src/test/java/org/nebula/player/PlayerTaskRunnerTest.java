package org.nebula.player;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the UUID parsing fix: player taskIds are TYPE@dim:uuid:coords. */
class PlayerTaskRunnerTest {

    @Test
    void parsePlayerUuidBits_extractsUuidAfterDimension() {
        // Format: PLAYER_MOVE@0:3f14b092-8c3c-49f1-8000-000000000001:10,64,-30
        String taskId = "PLAYER_MOVE@0:3f14b092-8c3c-49f1-8000-000000000001:10,64,-30";
        long uuidBits = PlayerTaskRunner.parseUuidBits(taskId);

        UUID expected = UUID.fromString("3f14b092-8c3c-49f1-8000-000000000001");
        assertEquals(expected.getLeastSignificantBits(), uuidBits);
    }

    @Test
    void parsePlayerUuidBits_inventoryUpdate() {
        // INVENTORY_UPDATE@1:aabbccdd-1122-3344-5566-778899001122:5
        String taskId = "INVENTORY_UPDATE@1:aabbccdd-1122-3344-5566-778899001122:5";
        long uuidBits = PlayerTaskRunner.parseUuidBits(taskId);

        UUID expected = UUID.fromString("aabbccdd-1122-3344-5566-778899001122");
        assertEquals(expected.getLeastSignificantBits(), uuidBits);
    }

    @Test
    void parsePlayerUuidBits_noAt_returnsZero() {
        assertEquals(0L, PlayerTaskRunner.parseUuidBits("PLAYER_MOVE"));
    }

    @Test
    void parsePlayerUuidBits_noColonAfterAt_returnsZero() {
        assertEquals(0L, PlayerTaskRunner.parseUuidBits("PLAYER_MOVE@0"));
    }

    @Test
    void parsePlayerUuidBits_invalidUuid_returnsZero() {
        // "0" is not a valid UUID string (the old dimension-parsed-as-UUID bug)
        assertEquals(0L, PlayerTaskRunner.parseUuidBits("PLAYER_MOVE@0:0:10,64,-30"));
    }

    @Test
    void parsePlayerUuidBits_dimensionZeroStillWorks() {
        // Dimension 0, UUID with LSB = 1
        String taskId = "PLAYER_MOVE@0:00000000-0000-0000-0000-000000000001:10,64,-30";
        long uuidBits = PlayerTaskRunner.parseUuidBits(taskId);
        assertEquals(1L, uuidBits);
    }

    @Test
    void parsePlayerUuidBits_dimensionOneStillWorks() {
        // COMBAT uses → separator; UUID LSB = 0x999 = 2457
        String taskId = "COMBAT@1:00000000-0000-0000-0000-000000000999→42";
        long uuidBits = PlayerTaskRunner.parseUuidBits(taskId);
        assertEquals(0x999L, uuidBits);
    }

    @Test
    void parsePlayerUuidBits_noCoordsAfterUuid() {
        // INVENTORY_UPDATE@1:uuid:slot  — UUID LSB = 0x42 = 66
        String taskId = "INVENTORY_UPDATE@1:00000000-0000-0000-0000-000000000042:5";
        long uuidBits = PlayerTaskRunner.parseUuidBits(taskId);
        assertEquals(0x42L, uuidBits);
    }
}
