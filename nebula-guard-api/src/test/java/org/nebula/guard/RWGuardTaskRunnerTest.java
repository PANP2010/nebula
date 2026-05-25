package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.DagExecutor;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;

import java.nio.file.Files;

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
}
