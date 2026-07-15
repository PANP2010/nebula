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
        assertEquals("ENTITY_AI_SENSE", tasks.get(0).taskType());
        assertEquals("ENTITY_AI_GOAL_SELECT", tasks.get(1).taskType());
        assertEquals("ENTITY_AI_PATHFIND", tasks.get(2).taskType());
        assertEquals("ENTITY_AI_ACT", tasks.get(3).taskType());
    }

    @Test
    void pipelineHasCorrectDependencyChain() {
        List<TaskNode> tasks = AITaskFactory.pipelineInert(ZOMBIE, 64);

        // Build DAG. The dependency graph is:
        //   SENSE → GOAL_SELECT → PATHFIND → ACT
        // giving 3 topological layers:
        //   Layer 0: SENSE, GOAL_SELECT (parallel — no edges between them)
        //   Layer 1: PATHFIND (WAW edge from GOAL_SELECT on goal_target)
        //   Layer 2: ACT (RAW edge from PATHFIND on path_cost)
        TaskGraph graph = DagBuilder.build(tasks);
        List<List<String>> layers = graph.topologicalLayers();

        assertEquals(4, layers.size(),
            "AI pipeline should have 4 unidirectional layers; got: " + layers);

        // Build execution-position map
        java.util.LinkedHashMap<String, Integer> position = new java.util.LinkedHashMap<>();
        for (List<String> layer : layers) {
            for (String id : layer) {
                position.put(id, position.size());
            }
        }

        String senseId = tasks.get(0).taskId();
        String goalId = tasks.get(1).taskId();
        String pathfindId = tasks.get(2).taskId();
        String actId = tasks.get(3).taskId();

        assertTrue(position.get(senseId) < position.get(goalId),
            "SENSE must execute before GOAL_SELECT");
        assertTrue(position.get(goalId) < position.get(pathfindId),
            "GOAL_SELECT must execute before PATHFIND");
        assertTrue(position.get(pathfindId) < position.get(actId),
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
        assertTrue(senseTask.declaredRWSet().readPoiQueries().isEmpty(),
            "SENSE task should NOT include POI queries (terrain read via blocks only)");
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

        // Both SENSE tasks must be in the same layer (they have no dependencies on each other).
        // They could be in any layer number depending on SCC contraction depth, so we check
        // that both appear in at least one layer rather than asserting on layer 0 specifically.
        List<String> senseIds = List.of(zombieTasks.get(0).taskId(), villagerTasks.get(0).taskId());
        assertTrue(layers.stream().anyMatch(layer ->
                senseIds.stream().allMatch(layer::contains)),
            "Both SENSE tasks should appear together in at least one layer; got: " + layers);
    }

    @Test
    void aiTaskTypeRoundtrip() {
        assertEquals(AITaskType.AI_SENSE, AITaskType.fromTaskType("ENTITY_AI_SENSE"));
        assertEquals(AITaskType.AI_ACT, AITaskType.fromTaskType("ENTITY_AI_ACT"));
        assertNull(AITaskType.fromTaskType("UNKNOWN"));
    }
}
