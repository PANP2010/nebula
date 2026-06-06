package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExplosionTaskFactoryTest {

    private static final WorldPos CENTER = new WorldPos(0, 100, 64, 100);

    private ExplosionSnapshot tntExplosion() {
        Set<WorldPos> blocks = Set.of(
            new WorldPos(0, 99, 64, 100),
            new WorldPos(0, 100, 64, 100),
            new WorldPos(0, 101, 64, 100),
            new WorldPos(0, 100, 65, 100),
            new WorldPos(0, 100, 63, 100)
        );
        List<Long> entities = List.of(1001L, 1002L, 1003L);
        return new ExplosionSnapshot(CENTER, 4.0f, 999L, blocks, entities);
    }

    @Test
    void subDagContainsAllLayers() {
        ExplosionSnapshot explosion = tntExplosion();
        List<TaskNode> tasks = ExplosionTaskFactory.createSubDag(explosion);

        // Should have: ray tasks + 1 collect + block destroy groups + entity damage tasks
        long rayCount = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_RAY_TRACE")).count();
        long collectCount = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_DESTRUCTION_COLLECT")).count();
        long destroyCount = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_BLOCK_DESTROY")).count();
        long damageCount = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_ENTITY_DAMAGE")).count();

        assertTrue(rayCount > 0, "should have ray trace tasks");
        assertEquals(1, collectCount, "should have exactly one destruction-collect");
        assertTrue(destroyCount > 0, "should have block destroy tasks");
        assertEquals(3, damageCount, "should have one damage task per entity");
    }

    @Test
    void rayTraceTasksAreParallel() {
        ExplosionSnapshot explosion = tntExplosion();
        List<TaskNode> tasks = ExplosionTaskFactory.createSubDag(explosion);

        // Ray trace tasks are read-only even when they conservatively read affected blocks.
        List<TaskNode> rayTasks = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_RAY_TRACE"))
            .toList();

        if (rayTasks.size() >= 2) {
            assertFalse(rayTasks.get(0).declaredRWSet()
                .hasWriteWriteConflictWith(rayTasks.get(1).declaredRWSet()));
        }
    }

    @Test
    void blockDestroyHasCorrectEvents() {
        ExplosionSnapshot explosion = tntExplosion();
        List<TaskNode> tasks = ExplosionTaskFactory.createSubDag(explosion);

        TaskNode destroyTask = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_BLOCK_DESTROY"))
            .findFirst().orElseThrow();

        assertTrue(destroyTask.declaredRWSet().writtenEvents().contains(EventType.BLOCK_UPDATE));
        assertTrue(destroyTask.declaredRWSet().writtenEvents().contains(EventType.ENTITY_SPAWNED));
        assertTrue(destroyTask.declaredRWSet().randomUsage().isPresent());
    }

    @Test
    void entityDamageTasksAreIndependent() {
        ExplosionSnapshot explosion = tntExplosion();
        List<TaskNode> tasks = ExplosionTaskFactory.createSubDag(explosion);

        List<TaskNode> damageTasks = tasks.stream()
            .filter(t -> t.taskType().equals("EXPLOSION_ENTITY_DAMAGE"))
            .toList();

        assertEquals(3, damageTasks.size());
        // Each writes different entity fields — no conflict between them
        for (int i = 0; i < damageTasks.size(); i++) {
            for (int j = i + 1; j < damageTasks.size(); j++) {
                assertFalse(damageTasks.get(i).declaredRWSet()
                    .hasWriteWriteConflictWith(damageTasks.get(j).declaredRWSet()),
                    "damage tasks for different entities should not conflict");
            }
        }
    }

    @Test
    void subDagIntegratesWithDagBuilder() {
        ExplosionSnapshot explosion = tntExplosion();
        List<TaskNode> tasks = ExplosionTaskFactory.createSubDag(explosion);

        // Should build without cycles or errors
        TaskGraph graph = DagBuilder.build(tasks);
        assertNotNull(graph);
        assertTrue(graph.topologicalLayers().size() >= 2,
            "explosion sub-DAG should have multiple layers due to read/write dependencies");
    }

    @Test
    void rayTraceTasksReadAffectedBlockSnapshot() {
        ExplosionSnapshot explosion = tntExplosion();
        TaskNode rayTask = ExplosionTaskFactory.createSubDag(explosion).stream()
            .filter(t -> t.taskType().equals("EXPLOSION_RAY_TRACE"))
            .findFirst().orElseThrow();

        for (WorldPos pos : explosion.affectedBlocks()) {
            assertTrue(rayTask.declaredRWSet().declaresBlockRead(pos),
                "ray trace should conservatively read affected block " + pos);
        }
        assertTrue(rayTask.declaredRWSet().writtenBlocks().isEmpty(),
            "ray trace should remain read-only");
    }

    @Test
    void rayTraceFallsBackToCenterWhenAffectedBlocksEmpty() {
        ExplosionSnapshot explosion = new ExplosionSnapshot(CENTER, 1.0f, -1, Set.of(), List.of());
        TaskNode rayTask = ExplosionTaskFactory.createSubDag(explosion).stream()
            .filter(t -> t.taskType().equals("EXPLOSION_RAY_TRACE"))
            .findFirst().orElseThrow();

        assertTrue(rayTask.declaredRWSet().declaresBlockRead(CENTER));
    }

    @Test
    void explosionTaskTypeRoundtrip() {
        assertEquals(ExplosionTaskType.RAY_TRACE,
            ExplosionTaskType.fromTaskType("EXPLOSION_RAY_TRACE"));
        assertEquals(ExplosionTaskType.ENTITY_DAMAGE,
            ExplosionTaskType.fromTaskType("EXPLOSION_ENTITY_DAMAGE"));
        assertNull(ExplosionTaskType.fromTaskType("UNKNOWN"));
    }

    @Test
    void explosionSnapshotRayCount() {
        ExplosionSnapshot tnt = tntExplosion();
        // TNT power=4.0, rayCount = 4*4*25 = 400
        assertEquals(400, tnt.rayCount());

        ExplosionSnapshot small = new ExplosionSnapshot(
            CENTER, 1.0f, -1, Set.of(CENTER), List.of());
        assertEquals(25, small.rayCount());
    }
}
