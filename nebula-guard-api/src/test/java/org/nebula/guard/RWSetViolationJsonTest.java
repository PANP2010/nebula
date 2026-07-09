package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RWSetViolationJsonTest {
    @Test
    void serializesViolationReportWithDeclaredRwSet() {
        RWSet declared = RWSet.builder()
            .readBlock(new WorldPos(0, 15, 64, 32))
            .readEntity(new EntityField(12345L, "position"))
            .build();
        RWSetViolation violation = RWSetViolation.create(
            15432L,
            "T_REDSTONE_WIRE_OVERWORLD_15_64_32",
            "REDSTONE_UPDATE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.entityField(new EntityField(12345L, "health")),
            declared,
            "Add entity read to REDSTONE_UPDATE: health"
        );

        String json = RWSetViolationJson.toJson(violation);

        assertTrue(json.contains("\"task_id\":\"T_REDSTONE_WIRE_OVERWORLD_15_64_32\""));
        assertTrue(json.contains("\"violation_type\":\"UNDECLARED_READ\""));
        assertTrue(json.contains("\"entities_read\""));
        assertTrue(json.contains("position"));
        assertTrue(json.contains("health"));
    }

    @Test
    void blockAccessTargetEmitsCleanIntFieldsAndRoundTrips() {
        // Regression for the B8-B3 follow-up: AccessTarget.block(pos) stores WorldPos.toString()
        // ("WorldPos[dimensionId=0, x=1, y=-59, z=0]"), which the serializer used to split on ','
        // and splat into "dimension", producing malformed JSON that fromJson() could not parse.
        WorldPos pos = new WorldPos(0, 2, -59, 0);
        RWSetViolation violation = RWSetViolation.create(
            15432L,
            "T_REDSTONE_WIRE_OVERWORLD_1_-59_0",
            "REDSTONE_WIRE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.block(pos),
            RWSet.empty(),
            "Add block read to REDSTONE_WIRE: " + pos
        );

        String json = RWSetViolationJson.toJson(violation);

        // Clean, machine-readable integer fields — not the raw record toString.
        assertTrue(json.contains("\"access_target\":{\"type\":\"BLOCK\",\"dimension\":0,\"x\":2,\"y\":-59,\"z\":0}"),
            "access_target should be clean int fields, was: " + json);
        // The raw WorldPos.toString() must not leak into the access_target object. (It may still
        // appear in the human-readable suggested_fix message, which is fine.)
        int atStart = json.indexOf("\"access_target\":");
        int atEnd = json.indexOf('}', atStart);
        String accessTargetObj = json.substring(atStart, atEnd + 1);
        assertFalse(accessTargetObj.contains("WorldPos["),
            "raw WorldPos.toString() leaked into access_target: " + accessTargetObj);

        // Round-trips: toJson -> fromJson yields the same BLOCK AccessTarget.
        RWSetViolation restored = RWSetViolationJson.fromJson(json);
        assertEquals(AccessTarget.block(pos), restored.accessTarget());
        assertEquals(AccessTargetType.BLOCK, restored.accessTarget().type());
    }

    // ── Round-trip coverage for the four NON-BLOCK AccessTarget types ─────────────────
    // Prior to this cycle only BLOCK round-tripped. The write side splatted every non-BLOCK
    // type under a single "value" holding the record toString(), while the read side expected
    // structured fields ("key"/"instance") or called *.parse() on the wrong serialization form.
    // So the C-series coverage loop (which emits ENTITY_FIELD / BLOCK_ENTITY_FIELD / GLOBAL /
    // RANDOM violations) could not machine-read its own JSONL back. These lock the whole
    // serializer, not just BLOCK.

    private static AccessTarget roundTrip(AccessTarget target) {
        RWSetViolation violation = RWSetViolation.create(
            42L, "T", "TASK_TYPE", ViolationType.UNDECLARED_READ, target, RWSet.empty(), "fix"
        );
        String json = RWSetViolationJson.toJson(violation);
        assertFalse(extractAccessTargetObject(json).contains("FieldPath["),
            "raw record toString leaked into access_target: " + json);
        return RWSetViolationJson.fromJson(json).accessTarget();
    }

    private static String extractAccessTargetObject(String json) {
        int atStart = json.indexOf("\"access_target\":");
        int atEnd = json.indexOf('}', atStart);
        return json.substring(atStart, atEnd + 1);
    }

    @Test
    void entityFieldAccessTargetRoundTrips() {
        AccessTarget target = AccessTarget.entityField(new EntityField(-987654321L, "movement.velocity"));
        AccessTarget restored = roundTrip(target);
        assertEquals(AccessTargetType.ENTITY_FIELD, restored.type());
        assertEquals(target, restored);
    }

    @Test
    void blockEntityFieldAccessTargetRoundTrips() {
        AccessTarget target = AccessTarget.blockEntityField(
            new BlockEntityField(new WorldPos(0, 12, -59, -8), "inventory.slot_3"));
        AccessTarget restored = roundTrip(target);
        assertEquals(AccessTargetType.BLOCK_ENTITY_FIELD, restored.type());
        assertEquals(target, restored);
    }

    @Test
    void globalKeyAccessTargetRoundTrips() {
        AccessTarget target = AccessTarget.globalKey(GlobalKey.REGION_SHOULD_SIGNAL);
        AccessTarget restored = roundTrip(target);
        assertEquals(AccessTargetType.GLOBAL_KEY, restored.type());
        assertEquals(target, restored);
    }

    @Test
    void randomAccessTargetRoundTrips() {
        AccessTarget target = AccessTarget.random(RandomInstance.WORLD_RANDOM);
        AccessTarget restored = roundTrip(target);
        assertEquals(AccessTargetType.RANDOM, restored.type());
        assertEquals(target, restored);
    }

    @Test
    void serializesPatchSuggestions() {
        RWSet declared = RWSet.empty();
        RWSetViolation violation = RWSetViolation.create(
            1L,
            "T",
            "TASK_TYPE",
            ViolationType.UNDECLARED_RANDOM_USAGE,
            AccessTarget.random(org.nebula.core.state.RandomInstance.WORLD_RANDOM),
            declared,
            "Declare random usage"
        );

        String json = RWSetViolationJson.suggestionsToJson(AnnotationPatchSuggestion.fromViolations(List.of(violation)));

        assertTrue(json.contains("\"task_type\":\"TASK_TYPE\""));
        assertTrue(json.contains("\"occurrences\":1"));
        assertTrue(json.contains("WORLD_RANDOM"));
    }
}
