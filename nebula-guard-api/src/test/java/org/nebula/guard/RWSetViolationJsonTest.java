package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;

import java.util.List;

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
