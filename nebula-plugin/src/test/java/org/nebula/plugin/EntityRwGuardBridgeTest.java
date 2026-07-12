package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskFactory;
import org.nebula.entity.EntityTaskRunner;
import org.nebula.entity.TerrainView;
import org.nebula.entity.actions.EntityMoveAction;
import org.nebula.guard.AccessTarget;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.guard.ViolationType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the entity tracer feeds real MOVE accesses into the RW checker (B8 C2). */
final class EntityRwGuardBridgeTest {

    private static final long ENTITY_ID = 42L;
    private static final EntitySnapshot SNAPSHOT = EntitySnapshot.of(ENTITY_ID, 3, 65, 7, 0);
    private static final EntityField POSITION = new EntityField(ENTITY_ID, "position");
    private static final EntityField VELOCITY = new EntityField(ENTITY_ID, "velocity");

    private static EntityPhysicsState seededState() {
        EntityPhysicsState state = new EntityPhysicsState();
        state.put(POSITION, new Vec3(3.25, 65.0, 7.25));
        state.put(VELOCITY, new Vec3(0.0, -0.2, 0.0));
        return state;
    }

    private static ActualAccessTrace runAndTrace(TaskNode task) throws Exception {
        EntityTaskRunner runner = new EntityTaskRunner(
            seededState(), id -> new EntityMoveAction(ENTITY_ID, 0),
            EntityRwGuardTracer.INSTANCE, null)
            .withTerrain(TerrainView.flatFloor(63));
        ThreadLocalAccessTrace.reset();
        runner.run(task);
        return ThreadLocalAccessTrace.snapshot();
    }

    @Test
    void moveActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        TaskNode task = EntityTaskFactory.moveInert(SNAPSHOT);
        ActualAccessTrace actual = runAndTrace(task);

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);

        assertTrue(violations.isEmpty(), "MOVE's real field/block accesses must be declared: " + violations);
        assertEquals(java.util.Set.of(POSITION, VELOCITY), actual.readEntityFields());
        assertEquals(java.util.Set.of(POSITION, VELOCITY), actual.writtenEntityFields());
        assertTrue(!actual.readBlocks().isEmpty(), "clean verdict must be corroborated by real terrain reads");
    }

    @Test
    void bridgeFlagsExactlyAnOmittedVelocityWrite() throws Exception {
        int dim = SNAPSHOT.dimensionId();
        WorldPos at = new WorldPos(dim, SNAPSHOT.x(), SNAPSHOT.y(), SNAPSHOT.z());
        RWSet.Builder builder = RWSet.builder()
            .readEntity(POSITION)
            .readEntity(VELOCITY)
            .readBlock(at)
            .readBlock(new WorldPos(dim, SNAPSHOT.x() + 1, SNAPSHOT.y(), SNAPSHOT.z()))
            .readBlock(new WorldPos(dim, SNAPSHOT.x() - 1, SNAPSHOT.y(), SNAPSHOT.z()))
            .readBlock(new WorldPos(dim, SNAPSHOT.x(), SNAPSHOT.y() + 1, SNAPSHOT.z()))
            .readBlock(new WorldPos(dim, SNAPSHOT.x(), SNAPSHOT.y(), SNAPSHOT.z() + 1))
            .readBlock(new WorldPos(dim, SNAPSHOT.x(), SNAPSHOT.y(), SNAPSHOT.z() - 1));
        for (int dy = 1; dy <= 5; dy++) {
            builder.readBlock(new WorldPos(dim, SNAPSHOT.x(), SNAPSHOT.y() - dy, SNAPSHOT.z()));
        }
        RWSet incomplete = builder
            .writeEntity(POSITION)
            .writeEvent(EventType.ENTITY_MOVED)
            .build();
        TaskNode task = new TaskNode(SNAPSHOT.taskId(org.nebula.entity.EntityTaskType.MOVE),
            "ENTITY_MOVE", incomplete, () -> { });

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, incomplete, runAndTrace(task));

        assertEquals(1, violations.size(), "only the omitted velocity write should be flagged: " + violations);
        assertEquals(ViolationType.UNDECLARED_WRITE, violations.getFirst().violationType());
        assertEquals(AccessTarget.entityField(VELOCITY), violations.getFirst().accessTarget());
    }
}
