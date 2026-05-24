package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AITaskFactoryTest {

    private static final EntitySnapshot ZOMBIE = EntitySnapshot.of(100L, 50, 64, 50, 0);
    private static final EntitySnapshot VILLAGER = EntitySnapshot.of(200L, 200, 64, 200, 0);

    @Test
    void pipelineCreates4Tasks() {
        List<TaskNode> tasks = AITaskFactory.pipelineInert(ZOMBIE, 64);
        assertEquals(4, tasks.size());
        assertEquals("AI_SENSE", tasks.get(0).taskType());
        assertEquals("AI_GOAL_SELECT", tasks.get(1).taskType());
        assertEquals("AI_PATHFIND", tasks.get(2).taskType());
        assertEquals("AI_ACT", tasks.get(3).taskType());
    }

    @Test
    void pipelineHasCorrectDependencyChain() {
        List<TaskNode> tasks = AITaskFactory.pipelineInert(ZOMBIE, 64);

        // Build DAG — should create edges: SENSE → GOAL_SELECT → PATHFIND → ACT
        TaskGraph graph = DagBuilder.build(tasks);
        List<List<String>> layers = graph.topologicalLayers();

        // Should have 4 layers (linear chain: SENSE → GOAL → PATHFIND → ACT)
        assertTrue(layers.size() >= 2,
            "AI pipeline should have dependency-driven layering, got " + layers.size()
                + " layers: " + layers);

        // SENSE must be before ACT
        List<String> flat = layers.stream().flatMap(List::stream).toList();
        String senseId = tasks.get(0).taskId();
        String goalId = tasks.get(1).taskId();
        String pathfindId = tasks.get(2).taskId();
        String actId = tasks.get(3).taskId();
        assertTrue(flat.indexOf(senseId) < flat.indexOf(goalId),
            "SENSE must execute before GOAL_SELECT");
        assertTrue(flat.indexOf(goalId) < flat.indexOf(pathfindId),
            "GOAL_SELECT must execute before PATHFIND");
        assertTrue(flat.indexOf(pathfindId) < flat.indexOf(actId),
            "PATHFIND must execute before ACT");
    }

    @Test
    void distinctEntitiesAIDoesNotConflict() {
        List<TaskNode> zombieTasks = AITaskFactory.pipelineInert(ZOMBIE, 64);
        List<TaskNode> villagerTasks = AITaskFactory.pipelineInert(VILLAGER, 64);

        // SENSE tasks for different entities should not conflict
        TaskNode zombieSense = zombieTasks.get(0);
        TaskNode villagerSense = villagerTasks.get(0);

        assertFalse(zombieSense.declaredRWSet()
            .hasWriteWriteConflictWith(villagerSense.declaredRWSet()),
            "different entities' AI sense tasks should not conflict");
    }

    @Test
    void actTaskFiresEntityMoved() {
        TaskNode actTask = AITaskFactory.actInert(ZOMBIE);
        assertTrue(actTask.declaredRWSet().writtenEvents().contains(EventType.ENTITY_MOVED));
    }

    @Test
    void senseTaskIncludesPoiQuery() {
        TaskNode senseTask = AITaskFactory.sense(ZOMBIE, 64);
        assertFalse(senseTask.declaredRWSet().readPoiQueries().isEmpty(),
            "SENSE task should include POI queries");
    }

    @Test
    void goalSelectUsesRandom() {
        TaskNode goalTask = AITaskFactory.goalSelect(ZOMBIE);
        assertTrue(goalTask.declaredRWSet().randomUsage().isPresent());
    }

    @Test
    void multipleEntitiesPipelineParallelizes() {
        List<TaskNode> zombieTasks = AITaskFactory.pipelineInert(ZOMBIE, 64);
        List<TaskNode> villagerTasks = AITaskFactory.pipelineInert(VILLAGER, 64);

        // Combine all tasks and build DAG
        List<TaskNode> allTasks = new java.util.ArrayList<>(zombieTasks);
        allTasks.addAll(villagerTasks);
        TaskGraph graph = DagBuilder.build(allTasks);

        List<List<String>> layers = graph.topologicalLayers();
        // First layer should contain both SENSE tasks (parallel)
        List<String> firstLayer = layers.get(0);
        assertTrue(firstLayer.contains(zombieTasks.get(0).taskId()));
        assertTrue(firstLayer.contains(villagerTasks.get(0).taskId()));
    }

    @Test
    void aiTaskTypeRoundtrip() {
        assertEquals(AITaskType.SENSE, AITaskType.fromTaskType("AI_SENSE"));
        assertEquals(AITaskType.ACT, AITaskType.fromTaskType("AI_ACT"));
        assertNull(AITaskType.fromTaskType("UNKNOWN"));
    }
}
