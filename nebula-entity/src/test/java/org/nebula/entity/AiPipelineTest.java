package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.entity.actions.AiPipelineActions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end AI pipeline test (arch doc §7.2): SENSE → GOAL_SELECT → PATHFIND →
 * ACT executing live on the entity state layer with layered RNG.
 *
 * <p>Verifies the four stages form a RAW dependency chain (serialised per entity
 * through shared {@code ai_state.*} fields), all stages run and mutate state,
 * and the whole pipeline replays deterministically and order-independently
 * across a population.
 */
class AiPipelineTest {

    private static final int DIM = 0;
    private static final long WORLD_SEED = 0x5EEDL;

    /** Registers live actions for one entity's 4 AI tasks, keyed by task ID. */
    private static void registerPipeline(Map<String, EntityTaskAction> actions,
                                         List<TaskNode> tasks, EntitySnapshot e) {
        long id = e.entityId();
        List<TaskNode> pipeline = AITaskFactory.pipelineInert(e, 8);
        for (TaskNode t : pipeline) {
            EntityTaskAction action = switch (t.taskType()) {
                case "ENTITY_AI_SENSE" -> AiPipelineActions.sense(id);
                case "ENTITY_AI_GOAL_SELECT" -> AiPipelineActions.goalSelect(id);
                case "ENTITY_AI_PATHFIND" -> AiPipelineActions.pathfind(id);
                case "ENTITY_AI_ACT" -> AiPipelineActions.act(id);
                default -> null;
            };
            actions.put(t.taskId(), action);
            tasks.add(t);
        }
    }

    @Test
    void pipelineStagesFormRawChainPerEntity() {
        EntitySnapshot e = EntitySnapshot.of(1L, 10, 64, 10, DIM);
        List<TaskNode> tasks = AITaskFactory.pipelineInert(e, 8);
        TaskGraph graph = DagBuilder.build(tasks);
        List<List<String>> layers = graph.topologicalLayers();

        // The 4 stages form 4 topological layers with no SCC cycles:
        //   Layer 0: SENSE     (no incoming dependencies)
        //   Layer 1: GOAL_SELECT (RAW from SENSE's sensed_entities write)
        //   Layer 2: PATHFIND   (RAW from GOAL_SELECT's goal_target write)
        //   Layer 3: ACT        (RAW from PATHFIND's path_cost write)
        assertEquals(4, layers.size(), "AI pipeline must produce 4 dependency layers; got: " + layers);
        for (List<String> layer : layers) {
            assertEquals(1, layer.size(), "Each layer must have exactly 1 task; got: " + layers);
        }
        assertEquals("ENTITY_AI_SENSE", graph.tasks().get(layers.get(0).get(0)).taskType());
        assertEquals("ENTITY_AI_ACT", graph.tasks().get(layers.get(3).get(0)).taskType());
    }

    @Test
    void pipelineExecutesAllStagesAndMutatesState() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position_snapshot"), 12.0);
        state.put(new EntityField(id, "health"), 20.0);
        state.put(new EntityField(id, "position"), 100.0);

        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        registerPipeline(actions, tasks, EntitySnapshot.of(id, 12, 64, 0, DIM));

        runTick(state, actions, tasks);

        // Every stage left its mark (new disjoint field namespaces).
        assertTrue(state.fields().contains(new EntityField(id, "sense.entities")));
        assertTrue(state.fields().contains(new EntityField(id, "goal.target")));
        assertTrue(state.fields().contains(new EntityField(id, "path.outcome")));
        assertTrue(state.fields().contains(new EntityField(id, "act.result")));
        // ACT moved the entity.
        assertTrue(state.getScalar(new EntityField(id, "position")) != 100.0,
            "ACT must advance position");
    }

    @Test
    void pipelineIsDeterministicAndOrderIndependent() throws Exception {
        Map<Long, Double> natural = runPopulation(false, 0L);
        Random shuffleRng = new Random(7);
        for (int trial = 0; trial < 4; trial++) {
            Map<Long, Double> shuffled = runPopulation(true, shuffleRng.nextLong());
            assertEquals(natural, shuffled,
                "AI pipeline results must be independent of task order (trial " + trial + ")");
        }
    }

    private Map<Long, Double> runPopulation(boolean shuffle, long shuffleSeed) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            long id = i + 1;
            ids.add(id);
            state.put(new EntityField(id, "position_snapshot"), (double) (i * 3));
            state.put(new EntityField(id, "health"), (i % 4 == 0) ? 3.0 : 20.0);
            state.put(new EntityField(id, "position"), (double) (i * 100));
            registerPipeline(actions, tasks, EntitySnapshot.of(id, i * 3, 64, 0, DIM));
        }

        if (shuffle) {
            Collections.shuffle(tasks, new Random(shuffleSeed));
        }
        runTick(state, actions, tasks);

        Map<Long, Double> result = new TreeMap<>();
        for (long id : ids) {
            // Combine final position + chosen goal into one comparable value.
            result.put(id, state.getScalar(new EntityField(id, "position")) * 10
                + state.getScalar(new EntityField(id, "goal.target")));
        }
        return result;
    }

    private static void runTick(EntityPhysicsState state, Map<String, EntityTaskAction> actions,
                                List<TaskNode> tasks) throws Exception {
        EntityTaskRunner runner = new EntityTaskRunner(
            state, actions::get, new LayeredRandomSource(WORLD_SEED));
        runner.beginTick(0L);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String taskId : layer) {
                TaskNode t = graph.tasks().get(taskId);
                if (t != null) runner.run(t);
            }
            runner.commitLayer();
            runner.resetLayer();
        }
    }
}
