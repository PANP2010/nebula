package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskGraphCacheTest {

    @Test
    void repeatedIdenticalWorkloadHitsCache() {
        TaskGraphCache.resetCounters();
        TaskGraphCache cache = new TaskGraphCache();
        List<TaskNode> tasks = conflictingTasks(new WorldPos(0, 0, 64, 0));
        long fp = TaskGraphCache.fingerprint(tasks);

        assertNull(cache.tryHit(tasks, fp));
        TaskGraph fresh = DagBuilder.build(tasks);
        cache.put(fp, fresh.edges(), fresh.topologicalLayers());

        TaskGraph cached = cache.tryHit(tasks, TaskGraphCache.fingerprint(tasks));

        assertNotNull(cached);
        assertEquals(fresh.edges(), cached.edges());
        assertEquals(1, TaskGraphCache.hits());
        assertEquals(1, TaskGraphCache.misses());
    }

    @Test
    void movedConcretePositionsMissCacheAndAvoidStaleEdges() {
        TaskGraphCache.resetCounters();
        TaskGraphCache cache = new TaskGraphCache();
        List<TaskNode> conflicting = conflictingTasks(new WorldPos(0, 0, 64, 0));
        long conflictingFp = TaskGraphCache.fingerprint(conflicting);
        TaskGraph conflictingGraph = DagBuilder.build(conflicting);

        assertEquals(Set.of(new DependencyEdge("writer", "reader", DependencyType.RAW)), conflictingGraph.edges());
        assertNull(cache.tryHit(conflicting, conflictingFp));
        cache.put(conflictingFp, conflictingGraph.edges(), conflictingGraph.topologicalLayers());

        List<TaskNode> independent = List.of(
            writer(new WorldPos(0, 0, 64, 0)),
            reader(new WorldPos(0, 20, 64, 0))
        );
        long independentFp = TaskGraphCache.fingerprint(independent);

        assertNotEquals(conflictingFp, independentFp);
        assertNull(cache.tryHit(independent, independentFp));
        assertTrue(DagBuilder.build(independent).edges().isEmpty());
        assertEquals(0, TaskGraphCache.hits());
        assertEquals(2, TaskGraphCache.misses());
    }

    @Test
    void fingerprintIsIndependentOfInputOrder() {
        List<TaskNode> tasks = List.of(
            reader(new WorldPos(0, 0, 64, 0)),
            writer(new WorldPos(0, 0, 64, 0)),
            TaskNode.inert("other", "test", RWSet.builder()
                .readBlock(new WorldPos(0, 5, 64, 5))
                .writeBlock(new WorldPos(0, 5, 65, 5))
                .build())
        );
        List<TaskNode> reversed = new ArrayList<>(tasks);
        java.util.Collections.reverse(reversed);

        assertEquals(TaskGraphCache.fingerprint(tasks), TaskGraphCache.fingerprint(reversed));
    }

    private static List<TaskNode> conflictingTasks(WorldPos pos) {
        return List.of(writer(pos), reader(pos));
    }

    private static TaskNode writer(WorldPos pos) {
        return TaskNode.inert("writer", "test", RWSet.builder()
            .writeBlock(pos)
            .build());
    }

    private static TaskNode reader(WorldPos pos) {
        return TaskNode.inert("reader", "test", RWSet.builder()
            .readBlock(pos)
            .build());
    }
}
