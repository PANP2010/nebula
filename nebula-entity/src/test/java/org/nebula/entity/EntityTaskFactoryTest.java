package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.*;

class EntityTaskFactoryTest {

    private static final EntitySnapshot ENTITY_A = EntitySnapshot.of(1L, 10, 64, 10, 0);
    private static final EntitySnapshot ENTITY_B = EntitySnapshot.of(2L, 12, 64, 10, 0);

    // ── EntityTaskType ────────────────────────────────────────────────────────

    @Test
    void fromTaskType_roundTrips() {
        for (EntityTaskType t : EntityTaskType.values()) {
            assertEquals(t, EntityTaskType.fromTaskType(t.taskType()));
        }
    }

    @Test
    void fromTaskType_unknownReturnsNull() {
        assertNull(EntityTaskType.fromTaskType("UNKNOWN"));
    }

    // ── EntitySnapshot ────────────────────────────────────────────────────────

    @Test
    void snapshot_bucketIndicesComputedCorrectly() {
        EntitySnapshot e = EntitySnapshot.of(99L, 64, 64, -64, 0);
        assertEquals(2, e.bucketX());
        assertEquals(-2, e.bucketZ());
    }

    @Test
    void snapshot_taskIdFormat() {
        String id = ENTITY_A.taskId(EntityTaskType.MOVE);
        assertTrue(id.startsWith("ENTITY_MOVE@0:1:"), "id=" + id);
    }

    // ── MOVE RW-set ───────────────────────────────────────────────────────────

    @Test
    void moveTask_readsPositionAndVelocity() {
        TaskNode t = EntityTaskFactory.moveInert(ENTITY_A);
        RWSet rw = t.declaredRWSet();

        assertTrue(rw.declaresEntityRead(new EntityField(1L, "position")));
        assertTrue(rw.declaresEntityRead(new EntityField(1L, "velocity")));
    }

    @Test
    void moveTask_writePosition() {
        TaskNode t = EntityTaskFactory.moveInert(ENTITY_A);
        assertTrue(t.declaredRWSet().declaresEntityWrite(new EntityField(1L, "position")));
    }

    @Test
    void moveTask_readsTerrainBlocks() {
        TaskNode t = EntityTaskFactory.moveInert(ENTITY_A);
        RWSet rw = t.declaredRWSet();
        // should read self + 6 adjacent blocks = 7 block reads
        assertEquals(7, rw.readBlocks().size());
        assertTrue(rw.readBlocks().contains(new WorldPos(0, 10, 64, 10)));
        assertTrue(rw.readBlocks().contains(new WorldPos(0, 11, 64, 10))); // +x
        assertTrue(rw.readBlocks().contains(new WorldPos(0, 10, 65, 10))); // +y
    }

    @Test
    void moveTask_firesEntityMoved() {
        assertTrue(EntityTaskFactory.moveInert(ENTITY_A)
            .declaredRWSet().writtenEvents().contains(EventType.ENTITY_MOVED));
    }

    // ── COLLISION RW-set (pure read) ─────────────────────────────────────────

    @Test
    void collisionTask_pureRead_noWrites() {
        TaskNode t = EntityTaskFactory.collisionInert(ENTITY_A, ENTITY_B);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.writtenEntityFields().isEmpty());
        assertTrue(rw.writtenBlocks().isEmpty());
        assertTrue(rw.writtenEvents().isEmpty());
    }

    @Test
    void collisionTask_readsBothPositions() {
        TaskNode t = EntityTaskFactory.collisionInert(ENTITY_A, ENTITY_B);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityRead(new EntityField(1L, "position")));
        assertTrue(rw.declaresEntityRead(new EntityField(2L, "position")));
    }

    @Test
    void collisionTask_idIsCommutative() {
        String ab = EntityTaskFactory.collisionInert(ENTITY_A, ENTITY_B).taskId();
        String ba = EntityTaskFactory.collisionInert(ENTITY_B, ENTITY_A).taskId();
        assertEquals(ab, ba);
    }

    // ── COLLISION_RESPONSE RW-set ─────────────────────────────────────────────

    @Test
    void collisionResponse_readsBothVelocities() {
        TaskNode t = EntityTaskFactory.collisionResponseInert(ENTITY_A, ENTITY_B);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityRead(new EntityField(1L, "velocity")));
        assertTrue(rw.declaresEntityRead(new EntityField(2L, "velocity")));
    }

    @Test
    void collisionResponse_writesBothVelocities() {
        TaskNode t = EntityTaskFactory.collisionResponseInert(ENTITY_A, ENTITY_B);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityWrite(new EntityField(1L, "velocity")));
        assertTrue(rw.declaresEntityWrite(new EntityField(2L, "velocity")));
    }

    @Test
    void collisionResponse_rwConflictsWithSelf() {
        RWSet rw = EntityTaskFactory.collisionResponseInert(ENTITY_A, ENTITY_B).declaredRWSet();
        assertTrue(rw.hasWriteWriteConflictWith(rw));
    }

    // ── AI_GOAL RW-set ───────────────────────────────────────────────────────

    @Test
    void aiGoal_readsAiState_writesGoalTarget() {
        TaskNode t = EntityTaskFactory.aiGoalInert(ENTITY_A);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityRead(new EntityField(1L, "ai_state")));
        assertTrue(rw.declaresEntityWrite(new EntityField(1L, "goal_target")));
        assertTrue(rw.randomUsage().isPresent());
        assertEquals(8, rw.randomUsage().get().maxCallsEstimate());
    }

    // ── ITEM_PICKUP RW-set ───────────────────────────────────────────────────

    @Test
    void itemPickup_inventoryAndItemRemoval() {
        EntitySnapshot picker = EntitySnapshot.of(10L, 5, 64, 5, 0);
        EntitySnapshot item   = EntitySnapshot.of(99L, 5, 64, 5, 0);
        TaskNode t = EntityTaskFactory.itemPickupInert(picker, item);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityWrite(new EntityField(10L, "inventory")));
        assertTrue(rw.declaresEntityWrite(new EntityField(99L, "removed")));
        assertTrue(rw.writtenEvents().contains(EventType.INVENTORY_CHANGED));
    }

    // ── DAMAGE RW-set ────────────────────────────────────────────────────────

    @Test
    void damage_writesDefenderHealth_readsAttackerDamage() {
        EntitySnapshot attacker = EntitySnapshot.of(1L, 10, 64, 10, 0);
        EntitySnapshot defender = EntitySnapshot.of(2L, 11, 64, 10, 0);
        TaskNode t = EntityTaskFactory.damageInert(attacker, defender);
        RWSet rw = t.declaredRWSet();
        assertTrue(rw.declaresEntityRead(new EntityField(1L, "attack_damage")));
        assertTrue(rw.declaresEntityRead(new EntityField(2L, "health")));
        assertTrue(rw.declaresEntityWrite(new EntityField(2L, "health")));
        assertTrue(rw.randomUsage().isPresent());
    }

    // ── RW conflict detection (integration) ─────────────────────────────────

    @Test
    void twoMoveTasksSameEntity_writeWriteConflict() {
        // Two MOVE tasks for the same entity writing the same position field
        RWSet rw1 = EntityTaskFactory.moveInert(ENTITY_A).declaredRWSet();
        RWSet rw2 = EntityTaskFactory.moveInert(ENTITY_A).declaredRWSet();
        assertTrue(rw1.hasWriteWriteConflictWith(rw2));
    }

    @Test
    void twoMoveTasksDifferentEntities_noConflict() {
        RWSet rw1 = EntityTaskFactory.moveInert(ENTITY_A).declaredRWSet();
        RWSet rw2 = EntityTaskFactory.moveInert(ENTITY_B).declaredRWSet();
        // Different entities → different EntityField keys → no conflict
        assertFalse(rw1.hasWriteWriteConflictWith(rw2));
        assertFalse(rw1.hasReadWriteConflictWith(rw2));
        assertFalse(rw1.hasWriteReadConflictWith(rw2));
    }

    @Test
    void moveAndCollisionResponse_rawConflict() {
        // COLLISION_RESPONSE writes velocity; MOVE reads velocity → RAW conflict
        RWSet moveRw = EntityTaskFactory.moveInert(ENTITY_A).declaredRWSet();
        RWSet respRw = EntityTaskFactory.collisionResponseInert(ENTITY_A, ENTITY_B).declaredRWSet();
        assertTrue(respRw.hasReadWriteConflictWith(moveRw) || moveRw.hasWriteReadConflictWith(respRw)
            || respRw.hasWriteReadConflictWith(moveRw));
    }
}
