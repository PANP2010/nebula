package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RWGuardModeTest {
    @Test
    void warnModeRecordsViolationsWithoutThrowing() {
        RWGuard.enable(RWGuardMode.WARN);
        TaskNode task = undeclaredHealthReadTask();

        assertDoesNotThrow(() -> RWGuard.executeTaskWithTracing(task));

        assertEquals(1, RWGuard.getLastViolations().size());
    }

    @Test
    void enforceModeThrowsOnViolations() {
        RWGuard.enable(RWGuardMode.ENFORCE);
        TaskNode task = undeclaredHealthReadTask();

        RWGuardViolationException ex = assertThrows(RWGuardViolationException.class, () -> RWGuard.executeTaskWithTracing(task));

        assertEquals(1, ex.violations().size());
    }

    @Test
    void testModeRecordsViolationsForPatchSuggestion() throws Exception {
        RWGuard.enable(RWGuardMode.TEST);
        TaskNode task = undeclaredHealthReadTask();

        RWGuard.executeTaskWithTracing(task);

        assertEquals(1, AnnotationPatchSuggestion.fromViolations(RWGuard.getLastViolations()).size());
    }

    @Test
    void disabledConfigExecutesWithoutTracing() throws Exception {
        RWGuard.configure(RWGuardConfig.defaults());
        TaskNode task = undeclaredHealthReadTask();

        RWGuard.executeTaskWithTracing(task);

        assertTrue(RWGuard.getLastViolations().isEmpty());
    }

    @Test
    void configuredViolationLogReceivesJsonLine() throws Exception {
        Path log = Files.createTempFile("nebula-rw-violations", ".jsonl");
        Files.deleteIfExists(log);
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.WARN).withViolationLog(log));
        TaskNode task = undeclaredHealthReadTask();

        RWGuard.executeTaskWithTracing(15432L, task);

        String payload = Files.readString(log);
        assertTrue(payload.contains("\"tick_number\":15432"));
        assertTrue(payload.contains("\"violation_type\":\"UNDECLARED_READ\""));
        assertTrue(payload.contains("health"));
    }

    private static TaskNode undeclaredHealthReadTask() {
        long entityId = 12345L;
        return new TaskNode("TEST_TASK", "TEST_ENTITY_TASK", RWSet.builder()
            .readEntity(new EntityField(entityId, "position"))
            .build(), () -> ThreadLocalAccessTrace.traceEntityRead(new EntityField(entityId, "health")));
    }
}
