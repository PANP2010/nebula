package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TickPipeline} — validates the full cycle of
 * DAG build → execute → microstep extend → re-layer → execute.
 */
class TickPipelineTest {

    @Test
    void simpleTasksNoMicroSteps() throws Exception {
        WorldPos pos1 = new WorldPos(0, 0, 64, 0);
        WorldPos pos2 = new WorldPos(0, 100, 64, 100);

        List<String> executed = new ArrayList<>();
        TaskNode taskA = new TaskNode("A", "OP",
            RWSet.builder().writeBlock(pos1).build(),
            () -> executed.add("A"));
        TaskNode taskB = new TaskNode("B", "OP",
            RWSet.builder().writeBlock(pos2).build(),
            () -> executed.add("B"));

        TaskGenerator noopGen = completed -> List.of();
        TickPipeline pipeline = new TickPipeline(noopGen, TaskRunner.DIRECT);

        TickPipeline.TickResult result = pipeline.execute(List.of(taskA, taskB));

        assertEquals(2, result.completedTaskIds().size());
        assertTrue(result.completedTaskIds().containsAll(List.of("A", "B")));
        assertEquals(0, result.microStepRounds());
        assertTrue(result.deferredToNextTick().isEmpty());
    }

    @Test
    void microStepPropagationChain() throws Exception {
        // Simulate a 3-step chain: A fires event → generates B → B fires event → generates C
        WorldPos posA = new WorldPos(0, 0, 64, 0);
        WorldPos posB = new WorldPos(0, 1, 64, 0);
        WorldPos posC = new WorldPos(0, 2, 64, 0);

        List<String> executed = new ArrayList<>();

        TaskNode taskA = new TaskNode("A", "WIRE",
            RWSet.builder().writeBlock(posA).writeEvent(EventType.BLOCK_UPDATE).build(),
            () -> executed.add("A"));

        TaskGenerator chainGen = completed -> {
            if (completed.taskId().equals("A")) {
                return List.of(new TaskNode("B", "WIRE",
                    RWSet.builder().writeBlock(posB).writeEvent(EventType.BLOCK_UPDATE).build(),
                    () -> executed.add("B")));
            }
            if (completed.taskId().equals("B")) {
                return List.of(new TaskNode("C", "WIRE",
                    RWSet.builder().writeBlock(posC).build(),
                    () -> executed.add("C")));
            }
            return List.of();
        };

        TickPipeline pipeline = new TickPipeline(chainGen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(taskA));

        assertEquals(List.of("A", "B", "C"), executed);
        assertEquals(3, result.completedTaskIds().size());
        assertEquals(2, result.microStepRounds());
    }

    @Test
    void dependencyOrderPreservedAcrossMicroSteps() throws Exception {
        // A writes pos, B reads pos (RAW). After B completes, generates C.
        WorldPos shared = new WorldPos(0, 5, 64, 5);
        WorldPos independent = new WorldPos(0, 50, 64, 50);

        List<String> executed = new ArrayList<>();

        TaskNode taskA = new TaskNode("A", "WRITE",
            RWSet.builder().writeBlock(shared).writeEvent(EventType.BLOCK_UPDATE).build(),
            () -> executed.add("A"));
        TaskNode taskB = new TaskNode("B", "READ",
            RWSet.builder().readBlock(shared).build(),
            () -> executed.add("B"));

        TaskGenerator gen = completed -> {
            if (completed.taskId().equals("B")) {
                return List.of(new TaskNode("C", "OP",
                    RWSet.builder().writeBlock(independent).build(),
                    () -> executed.add("C")));
            }
            return List.of();
        };

        TickPipeline pipeline = new TickPipeline(gen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(taskA, taskB));

        // A before B (RAW dependency), C after B (microstep)
        assertTrue(executed.indexOf("A") < executed.indexOf("B"));
        assertTrue(executed.indexOf("B") < executed.indexOf("C"));
        assertEquals(1, result.microStepRounds());
    }

    @Test
    void microStepLimitDegradesGracefully() throws Exception {
        WorldPos pos = new WorldPos(0, 0, 64, 0);

        // Generator that always produces a new task (infinite chain)
        TaskGenerator infiniteGen = completed -> {
            int n = Integer.parseInt(completed.taskId().substring(1));
            String nextId = "T" + (n + 1);
            return List.of(new TaskNode(nextId, "WIRE",
                RWSet.builder()
                    .writeBlock(new WorldPos(0, n + 1, 64, 0))
                    .writeEvent(EventType.BLOCK_UPDATE)
                    .build(),
                () -> {}));
        };

        TaskNode seed = new TaskNode("T0", "WIRE",
            RWSet.builder().writeBlock(pos).writeEvent(EventType.BLOCK_UPDATE).build(),
            () -> {});

        TickPipeline pipeline = new TickPipeline(infiniteGen, TaskRunner.DIRECT, 5);

        // §16.1: overflow is a graceful degradation, not a tick-crashing throw.
        // The tick completes, flags the overflow, and stops the microstep loop at the cap.
        TickPipeline.TickResult result = pipeline.execute(List.of(seed));
        assertTrue(result.microStepOverflowed(), "overflow flag should be set");
        assertEquals(5, result.microStepRounds(), "microsteps should stop at the cap");
    }

    @Test
    void compositeGeneratorInPipeline() throws Exception {
        WorldPos posA = new WorldPos(0, 0, 64, 0);
        WorldPos posB = new WorldPos(0, 10, 64, 0);
        WorldPos posC = new WorldPos(0, 20, 64, 0);

        List<String> executed = new ArrayList<>();

        TaskNode taskA = new TaskNode("A", "TYPE_X",
            RWSet.builder().writeBlock(posA).writeEvent(EventType.BLOCK_UPDATE).build(),
            () -> executed.add("A"));

        TaskGenerator genX = completed -> {
            if ("TYPE_X".equals(completed.taskType())) {
                return List.of(new TaskNode("B", "TYPE_Y",
                    RWSet.builder().writeBlock(posB).writeEvent(EventType.ENTITY_MOVED).build(),
                    () -> executed.add("B")));
            }
            return List.of();
        };
        TaskGenerator genY = completed -> {
            if ("TYPE_Y".equals(completed.taskType())) {
                return List.of(new TaskNode("C", "TYPE_Z",
                    RWSet.builder().writeBlock(posC).build(),
                    () -> executed.add("C")));
            }
            return List.of();
        };

        CompositeTaskGenerator composite = new CompositeTaskGenerator(genX, genY);
        TickPipeline pipeline = new TickPipeline(composite, TaskRunner.DIRECT);

        TickPipeline.TickResult result = pipeline.execute(List.of(taskA));

        assertEquals(List.of("A", "B", "C"), executed);
        assertEquals(2, result.microStepRounds());
    }
}
