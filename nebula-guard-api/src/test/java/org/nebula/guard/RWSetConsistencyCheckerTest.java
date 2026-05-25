package org.nebula.guard;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RWSetConsistencyCheckerTest {
    @Test
    void detectsUndeclaredEntityRead() {
        long entityId = 12345L;
        TaskNode task = TaskNode.inert("TEST_TASK", "TEST_ENTITY_TASK", RWSet.builder()
            .readEntity(new EntityField(entityId, "position"))
            .build());

        ThreadLocalAccessTrace.reset();
        ThreadLocalAccessTrace.traceEntityRead(new EntityField(entityId, "position"));
        ThreadLocalAccessTrace.traceEntityRead(new EntityField(entityId, "health"));

        List<RWSetViolation> violations = RWSetConsistencyChecker.check(task, ThreadLocalAccessTrace.snapshot());

        assertEquals(1, violations.size());
        assertEquals(ViolationType.UNDECLARED_READ, violations.getFirst().violationType());
        assertEquals(AccessTarget.entityField(new EntityField(entityId, "health")), violations.getFirst().accessTarget());
    }

    @Test
    void detectsUndeclaredRandomUsage() {
        TaskNode task = TaskNode.inert("RANDOM_TASK", "RANDOM_TASK", RWSet.empty());

        ThreadLocalAccessTrace.reset();
        ThreadLocalAccessTrace.traceRandomCall(RandomInstance.WORLD_RANDOM);

        List<RWSetViolation> violations = RWSetConsistencyChecker.check(task, ThreadLocalAccessTrace.snapshot());

        assertEquals(1, violations.size());
        assertEquals(ViolationType.UNDECLARED_RANDOM_USAGE, violations.getFirst().violationType());
    }

    @Test
    void acceptsDeclaredBlockRead() {
        WorldPos pos = new WorldPos(0, 15, 64, 32);
        TaskNode task = TaskNode.inert("BLOCK_TASK", "REDSTONE_UPDATE", RWSet.builder()
            .readBlock(pos)
            .build());

        ThreadLocalAccessTrace.reset();
        ThreadLocalAccessTrace.traceBlockRead(pos);

        assertTrue(RWSetConsistencyChecker.check(task, ThreadLocalAccessTrace.snapshot()).isEmpty());
    }
}
