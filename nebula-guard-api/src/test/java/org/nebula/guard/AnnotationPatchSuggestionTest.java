package org.nebula.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for AnnotationPatchSuggestion.
 * Validates auto-generated annotation patch suggestions from violations.
 */
class AnnotationPatchSuggestionTest {

    @Test
    void generatesBlockReadPatch() {
        RWSetViolation violation = RWSetViolation.create(
            100L, "TASK_001", "REDSTONE_UPDATE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.block(new org.nebula.core.state.WorldPos(0, 10, 64, 10)),
            org.nebula.core.rw.RWSet.empty(),
            "Add block read"
        );

        List<AnnotationPatchSuggestion> suggestions =
            AnnotationPatchSuggestion.fromViolations(List.of(violation));

        assertEquals(1, suggestions.size());
        AnnotationPatchSuggestion suggestion = suggestions.get(0);
        assertEquals("TASK_001", suggestion.taskId());
        assertEquals("REDSTONE_UPDATE", suggestion.taskType());
        assertTrue(suggestion.suggestedFix().contains("Add block read"));
    }

    @Test
    void generatesEntityWritePatch() {
        RWSetViolation violation = RWSetViolation.create(
            100L, "ZOMBIE_AI", "ENTITY_AI",
            ViolationType.UNDECLARED_WRITE,
            AccessTarget.entityField(
                new org.nebula.core.state.EntityField(
                    12345L,
                    new org.nebula.core.state.FieldPath("position")
                )
            ),
            org.nebula.core.rw.RWSet.empty(),
            "Add entity write"
        );

        List<AnnotationPatchSuggestion> suggestions =
            AnnotationPatchSuggestion.fromViolations(List.of(violation));

        assertEquals(1, suggestions.size());
        assertTrue(suggestions.get(0).suggestedFix().contains("Add entity write"));
    }

    @Test
    void groupsViolationsByTask() {
        // Two violations for the same task with SAME access target
        org.nebula.core.state.WorldPos pos = new org.nebula.core.state.WorldPos(0, 0, 64, 0);
        RWSetViolation v1 = RWSetViolation.create(
            100L, "TASK_A", "TYPE",
            ViolationType.UNDECLARED_READ,
            AccessTarget.block(pos),
            org.nebula.core.rw.RWSet.empty(),
            "fix1"
        );

        RWSetViolation v2 = RWSetViolation.create(
            100L, "TASK_A", "TYPE",
            ViolationType.UNDECLARED_WRITE,
            AccessTarget.block(pos),  // Same position
            org.nebula.core.rw.RWSet.empty(),
            "fix2"
        );

        List<AnnotationPatchSuggestion> suggestions =
            AnnotationPatchSuggestion.fromViolations(List.of(v1, v2));

        // Should be grouped into single suggestion for TASK_A (same taskId, taskType, accessTarget)
        assertEquals(1, suggestions.size());
        assertEquals("fix1", suggestions.get(0).suggestedFix());
        assertEquals(2, suggestions.get(0).occurrences());
    }

    @Test
    void handlesEmptyViolations() {
        List<AnnotationPatchSuggestion> suggestions =
            AnnotationPatchSuggestion.fromViolations(List.of());

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void suggestionContainsViolationDetails() {
        RWSetViolation violation = RWSetViolation.create(
            500L, "COMPLEX_TASK", "AI_TASK",
            ViolationType.UNDECLARED_RANDOM_USAGE,
            AccessTarget.random(org.nebula.core.state.RandomInstance.ENTITY_RANDOM),
            org.nebula.core.rw.RWSet.empty(),
            "Declare random"
        );

        List<AnnotationPatchSuggestion> suggestions =
            AnnotationPatchSuggestion.fromViolations(List.of(violation));

        assertEquals(1, suggestions.size());
        AnnotationPatchSuggestion suggestion = suggestions.get(0);
        assertEquals("COMPLEX_TASK", suggestion.taskId());
        assertTrue(suggestion.suggestedFix().contains("Declare random"));
    }
}
