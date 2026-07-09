package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.DagExecutor;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RWGuardTaskRunnerTest {
    @Test
    void dagExecutorCanRunTasksThroughRwGuard() throws Exception {
        var log = Files.createTempFile("nebula-guard-runner", ".jsonl");
        Files.deleteIfExists(log);
        long entityId = 77L;
        TaskNode task = new TaskNode("T_AI", "AI_TICK", RWSet.builder()
            .readEntity(new EntityField(entityId, "position"))
            .build(), () -> ThreadLocalAccessTrace.traceEntityRead(new EntityField(entityId, "health")));
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.WARN).withViolationLog(log));

        DagExecutor.execute(DagBuilder.build(java.util.List.of(task)), new RWGuardTaskRunner(15432L));

        assertEquals(1, RWGuard.getLastViolations().size());
        String payload = Files.readString(log);
        assertTrue(payload.contains("\"tick_number\":15432"));
        assertTrue(payload.contains("\"task_id\":\"T_AI\""));
    }

    @Test
    void enforceModePropagatesGuardViolationThroughDagExecutor() {
        long entityId = 77L;
        TaskNode task = new TaskNode("T_AI", "AI_TICK", RWSet.builder()
            .readEntity(new EntityField(entityId, "position"))
            .build(), () -> ThreadLocalAccessTrace.traceEntityRead(new EntityField(entityId, "health")));
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.ENFORCE));

        DagExecutionException ex = assertThrows(
            DagExecutionException.class,
            () -> DagExecutor.execute(DagBuilder.build(java.util.List.of(task)), new RWGuardTaskRunner(1L))
        );

        assertInstanceOf(RWGuardViolationException.class, ex.failures().getFirst());
    }

    @Test
    void wiredGuardFlagsExactlyTheOmittedAccessWithTheRightTarget() throws Exception {
        // B1: the isolated RWSetConsistencyCheckerTest asserts the right AccessTarget, and
        // dagExecutorCanRunTasksThroughRwGuard asserts the full runner->DAG->trace->checker->report
        // stack surfaces *a* violation — but nothing asserts that the wired path surfaces exactly the
        // omitted access with the correct target. A tracer/checker mismatch would slip through the
        // count-only assertion. Pin the whole A/B/C path down to the AccessTarget here.
        long entityId = 77L;
        EntityField declared = new EntityField(entityId, "position");
        EntityField omitted = new EntityField(entityId, "health");
        TaskNode task = new TaskNode("T_AI", "AI_TICK", RWSet.builder()
            .readEntity(declared)
            .build(), () -> {
                ThreadLocalAccessTrace.traceEntityRead(declared); // declared read — must NOT be flagged
                ThreadLocalAccessTrace.traceEntityRead(omitted);  // undeclared read — must be flagged
            });
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.WARN));

        DagExecutor.execute(DagBuilder.build(List.of(task)), new RWGuardTaskRunner(4242L));

        List<RWSetViolation> violations = RWGuard.getLastViolations();
        assertEquals(1, violations.size(), "only the omitted access should be flagged, not the declared one");
        RWSetViolation violation = violations.getFirst();
        assertEquals(ViolationType.UNDECLARED_READ, violation.violationType());
        assertEquals(AccessTarget.entityField(omitted), violation.accessTarget());
        assertEquals("T_AI", violation.taskId());
        assertEquals("AI_TICK", violation.taskType());
        assertEquals(4242L, violation.tickNumber());
    }

    @Test
    void wiredGuardReportsNoViolationsWhenTraceMatchesDeclaredSet() throws Exception {
        // The other half of B1: prove the wired path does not cry wolf. A task that touches exactly
        // its declared read set must trace clean through the full runner/DAG/checker stack.
        long entityId = 88L;
        EntityField declared = new EntityField(entityId, "position");
        TaskNode task = new TaskNode("T_CLEAN", "AI_TICK", RWSet.builder()
            .readEntity(declared)
            .build(), () -> ThreadLocalAccessTrace.traceEntityRead(declared));
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.ENFORCE));

        // ENFORCE would throw on any violation; a clean trace must complete normally.
        DagExecutor.execute(DagBuilder.build(List.of(task)), new RWGuardTaskRunner(7L));

        assertTrue(RWGuard.getLastViolations().isEmpty(), "a fully-declared access set must trace clean");
    }
}
