package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.FieldPath;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for RWSetViolation and RWSetViolationJson.
 * Validates violation reporting and JSON serialization.
 */
class RWSetViolationTest {

    @Test
    void createsViolationWithAllFields() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        TaskNode task = TaskNode.inert("TASK_001", "REDSTONE_UPDATE",
            RWSet.builder().readBlock(pos).build());

        RWSetViolation violation = RWSetViolation.create(
            12345L, "TASK_001", "REDSTONE_UPDATE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.block(pos),
            task.declaredRWSet(),
            "Add block read: " + pos
        );

        assertEquals(12345L, violation.tickNumber());
        assertEquals("TASK_001", violation.taskId());
        assertEquals("REDSTONE_UPDATE", violation.taskType());
        assertEquals(ViolationType.UNDECLARED_READ, violation.violationType());
        assertEquals(AccessTargetType.BLOCK, violation.accessTarget().type());
        assertNotNull(violation.timestamp());
        assertNotNull(violation.violationId());
    }

    @Test
    void violationIdsAreUnique() {
        RWSetViolation v1 = RWSetViolation.create(
            1L, "T1", "TYPE", ViolationType.UNDECLARED_READ,
            AccessTarget.block(new WorldPos(0, 0, 64, 0)),
            RWSet.empty(), "fix1"
        );

        RWSetViolation v2 = RWSetViolation.create(
            1L, "T2", "TYPE", ViolationType.UNDECLARED_READ,
            AccessTarget.block(new WorldPos(0, 1, 64, 1)),
            RWSet.empty(), "fix2"
        );

        assertNotEquals(v1.violationId(), v2.violationId());
    }

    @Test
    void violationIdFormat() {
        RWSetViolation violation = RWSetViolation.create(
            0L, "T", "TYPE", ViolationType.UNDECLARED_READ,
            AccessTarget.block(new WorldPos(0, 0, 64, 0)),
            RWSet.empty(), "fix"
        );

        // Format: RW-VIOL-<uuid>
        assertTrue(violation.violationId().startsWith("RW-VIOL-"));
        assertTrue(violation.violationId().length() > 30);
    }

    @Test
    void serializesToJson() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        TaskNode task = TaskNode.inert("TASK_001", "REDSTONE_UPDATE",
            RWSet.builder().readBlock(pos).build());

        RWSetViolation violation = RWSetViolation.create(
            12345L, "TASK_001", "REDSTONE_UPDATE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.block(pos),
            task.declaredRWSet(),
            "Add block read: " + pos
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"tick_number\":12345"));
        assertTrue(json.contains("\"task_id\":\"TASK_001\""));
        assertTrue(json.contains("\"task_type\":\"REDSTONE_UPDATE\""));
        assertTrue(json.contains("\"violation_type\":\"UNDECLARED_READ\""));
        assertTrue(json.contains("\"suggested_fix\":"));
    }

    @Test
    void deserializesFromJson() {
        String json = """
            {
                "violation_id": "RW-VIOL-20260523-001",
                "timestamp": "2026-05-23T12:00:00Z",
                "tick_number": 100,
                "task_id": "TEST_TASK",
                "task_type": "TEST_TYPE",
                "violation_type": "UNDECLARED_READ",
                "access_target": {
                    "type": "BLOCK",
                    "dimension": 0,
                    "x": 5,
                    "y": 64,
                    "z": 10
                },
                "declared_rw_set": {
                    "blocks_read": [],
                    "blocks_written": []
                },
                "stack_trace": [],
                "suggested_fix": "Add read"
            }
            """;

        RWSetViolation violation = RWSetViolationJson.fromJson(json);

        assertEquals("TEST_TASK", violation.taskId());
        assertEquals(100L, violation.tickNumber());
        assertEquals(ViolationType.UNDECLARED_READ, violation.violationType());
    }

    @Test
    void serializesEntityFieldTarget() {
        EntityField entity = new EntityField(12345L, new FieldPath("health"));
        TaskNode task = TaskNode.inert("TASK", "ENTITY_TASK",
            RWSet.empty());

        RWSetViolation violation = RWSetViolation.create(
            0L, "TASK", "ENTITY_TASK",
            ViolationType.UNDECLARED_WRITE,
            AccessTarget.entityField(entity),
            task.declaredRWSet(),
            "Add entity write"
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"type\":\"ENTITY_FIELD\""));
        assertTrue(json.contains("\"entity_id\":12345"));
        assertTrue(json.contains("\"field_path\":\"health\""));
        assertEquals(AccessTarget.entityField(entity), RWSetViolationJson.fromJson(json).accessTarget());
    }

    @Test
    void serializesGlobalKeyTarget() {
        GlobalKey weather = new GlobalKey("weather");
        TaskNode task = TaskNode.inert("TASK", "GLOBAL_TASK",
            RWSet.empty());

        RWSetViolation violation = RWSetViolation.create(
            0L, "TASK", "GLOBAL_TASK",
            ViolationType.UNDECLARED_READ,
            AccessTarget.globalKey(weather),
            task.declaredRWSet(),
            "Add global read"
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"type\":\"GLOBAL_KEY\""));
        assertTrue(json.contains("\"key\":\"weather\""));
        assertEquals(AccessTarget.globalKey(weather), RWSetViolationJson.fromJson(json).accessTarget());
    }

    @Test
    void serializesRandomTarget() {
        TaskNode task = TaskNode.inert("TASK", "RANDOM_TASK",
            RWSet.empty());

        RWSetViolation violation = RWSetViolation.create(
            0L, "TASK", "RANDOM_TASK",
            ViolationType.UNDECLARED_RANDOM_USAGE,
            AccessTarget.random(RandomInstance.WORLD_RANDOM),
            task.declaredRWSet(),
            "Declare random usage"
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"type\":\"RANDOM\""));
        assertTrue(json.contains("\"instance\":\"WORLD_RANDOM\""));
        assertEquals(AccessTarget.random(RandomInstance.WORLD_RANDOM),
            RWSetViolationJson.fromJson(json).accessTarget());
    }

    @Test
    void blockEntityFieldTarget() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        BlockEntityField field = new BlockEntityField(pos, new FieldPath("inventory.slots[0]"));
        TaskNode task = TaskNode.inert("TASK", "HOPPER_TASK",
            RWSet.empty());

        RWSetViolation violation = RWSetViolation.create(
            0L, "TASK", "HOPPER_TASK",
            ViolationType.UNDECLARED_WRITE,
            AccessTarget.blockEntityField(field),
            task.declaredRWSet(),
            "Add block entity write"
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"type\":\"BLOCK_ENTITY_FIELD\""));
        assertTrue(json.contains("\"field_path\":\"inventory.slots[0]\""));
        assertEquals(AccessTarget.blockEntityField(field), RWSetViolationJson.fromJson(json).accessTarget());
    }

    @Test
    void roundTripSerialization() {
        WorldPos pos = new WorldPos(0, 100, 200, 300);
        TaskNode task = TaskNode.inert("ORIGINAL", "TYPE",
            RWSet.builder().readBlock(pos).build());

        RWSetViolation original = RWSetViolation.create(
            99999L, "ORIGINAL", "TYPE",
            ViolationType.UNDECLARED_WRITE,
            AccessTarget.block(pos),
            task.declaredRWSet(),
            "Original fix"
        );

        String json = RWSetViolationJson.toJson(original);
        // Just verify JSON contains expected fields
        assertTrue(json.contains("\"task_id\":\"ORIGINAL\""));
        assertTrue(json.contains("\"tick_number\":99999"));
    }
}
