package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.EntityMoveAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the entity RW-guard tracer and per-task bracket seams (B8 C2). */
class EntityTaskRunnerGuardSeamTest {

    private static final long ENTITY_ID = 42L;
    private static final EntitySnapshot SNAPSHOT = EntitySnapshot.of(ENTITY_ID, 3, 65, 7, 0);

    private static final class RecordingTracer implements EntityAccessTracer {
        final Set<EntityField> reads = new LinkedHashSet<>();
        final Set<EntityField> writes = new LinkedHashSet<>();
        final Set<WorldPos> blockReads = new LinkedHashSet<>();

        @Override public void onFieldRead(EntityField field) { reads.add(field); }
        @Override public void onFieldWrite(EntityField field) { writes.add(field); }
        @Override public void onBlockRead(WorldPos pos) { blockReads.add(pos); }
    }

    private static final class RecordingGuard implements EntityTaskGuardHook {
        final List<String> events = new ArrayList<>();
        @Override public void beforeTask(TaskNode task) { events.add("before:" + task.taskId()); }
        @Override public void afterTask(TaskNode task) { events.add("after:" + task.taskId()); }
    }

    private static EntityPhysicsState seededState() {
        EntityPhysicsState state = new EntityPhysicsState();
        state.put(new EntityField(ENTITY_ID, "position"), new Vec3(3.25, 65.0, 7.25));
        state.put(new EntityField(ENTITY_ID, "velocity"), new Vec3(0.0, -0.2, 0.0));
        return state;
    }

    @Test
    void tracerObservesRealMoveFieldsAndTerrainReads() throws Exception {
        EntityPhysicsState state = seededState();
        RecordingTracer tracer = new RecordingTracer();
        EntityTaskRunner runner = new EntityTaskRunner(
            state, id -> new EntityMoveAction(ENTITY_ID, 0), tracer, null)
            .withTerrain(TerrainView.flatFloor(63));

        new EntityTickExecutor(runner).executeTick(List.of(EntityTaskFactory.moveInert(SNAPSHOT)));

        assertEquals(Set.of(
            new EntityField(ENTITY_ID, "position"),
            new EntityField(ENTITY_ID, "velocity")), tracer.reads);
        assertEquals(tracer.reads, tracer.writes);
        assertTrue(!tracer.blockReads.isEmpty(), "MOVE must report the terrain cells it probes");
    }

    @Test
    void guardHookBracketsTaskEvenWhenActionThrows() {
        EntityPhysicsState state = seededState();
        TaskNode task = EntityTaskFactory.moveInert(SNAPSHOT);
        RecordingGuard guard = new RecordingGuard();
        EntityTaskRunner runner = new EntityTaskRunner(
            state, id -> ctx -> { throw new IllegalStateException("boom"); }, null, guard);

        try {
            runner.run(task);
        } catch (Exception expected) {
            // propagated after the bracket closes
        }

        assertEquals(List.of("before:" + task.taskId(), "after:" + task.taskId()), guard.events);
    }
}
