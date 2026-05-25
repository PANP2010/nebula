package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MicroStepExtenderTest {

    private static final int DIM = 0;

    private static WorldPos pos(int x) {
        return new WorldPos(DIM, x, 64, 0);
    }

    private static TaskNode writeTask(String id, int writeX) {
        RWSet rw = RWSet.builder().writeBlock(pos(writeX)).build();
        return TaskNode.inert(id, "write", rw);
    }

    private static TaskNode readWriteTask(String id, int readX, int writeX) {
        RWSet rw = RWSet.builder().readBlock(pos(readX)).writeBlock(pos(writeX)).build();
        return TaskNode.inert(id, "rw", rw);
    }

    @Test
    void noTriggerTasksReachesFixedPointImmediately() {
        TaskGraph initial = DagBuilder.build(List.of(writeTask("T0", 0)));
        MicroStepExtender extender = new MicroStepExtender(task -> List.of(), 256);
        extender.seed(initial);

        boolean extended = extender.extend(List.of("T0"));
        assertFalse(extended, "No generated tasks → fixed point on first call");
        assertEquals(0, extender.microStepCount());
    }

    @Test
    void singlePropagationStepAddsDownstreamTask() {
        TaskNode t0 = writeTask("T0", 0);
        TaskGraph initial = DagBuilder.build(List.of(t0));

        // Generator: T0 writing pos(0) triggers T1 reading pos(0) and writing pos(1)
        TaskNode t1 = readWriteTask("T1", 0, 1);
        MicroStepExtender extender = new MicroStepExtender(task -> {
            if ("T0".equals(task.taskId())) return List.of(t1);
            return List.of();
        }, 256);
        extender.seed(initial);

        boolean extended = extender.extend(List.of("T0"));
        assertTrue(extended);
        assertEquals(1, extender.microStepCount());

        TaskGraph graph = extender.currentGraph();
        assertTrue(graph.tasks().containsKey("T1"));
    }

    @Test
    void propagationStopsAtFixedPoint() {
        TaskNode t0 = writeTask("T0", 0);
        TaskNode t1 = readWriteTask("T1", 0, 1);
        TaskGraph initial = DagBuilder.build(List.of(t0));

        MicroStepExtender extender = new MicroStepExtender(task -> {
            if ("T0".equals(task.taskId())) return List.of(t1);
            return List.of(); // T1 triggers nothing
        }, 256);
        extender.seed(initial);

        // First extension: T1 is added
        boolean round1 = extender.extend(List.of("T0"));
        assertTrue(round1);

        // Second extension: T1 generates nothing
        boolean round2 = extender.extend(List.of("T1"));
        assertFalse(round2, "T1 generates no tasks → fixed point");
        assertEquals(1, extender.microStepCount());
    }

    @Test
    void conflictWithExecutedTaskIsDeferred() {
        // T0 writes pos(0). T1 (generated from T0) also writes pos(0).
        // T0 is already executed → T1 conflicts with executed task → deferred.
        TaskNode t0 = writeTask("T0", 0);
        TaskNode t1 = writeTask("T1", 0); // conflict with T0 on pos(0)
        TaskGraph initial = DagBuilder.build(List.of(t0));

        MicroStepExtender extender = new MicroStepExtender(task -> {
            if ("T0".equals(task.taskId())) return List.of(t1);
            return List.of();
        }, 256);
        extender.seed(initial);
        extender.markExecuted("T0");

        extender.extend(List.of("T0"));

        // T1 must NOT be in current graph (deferred)
        assertFalse(extender.currentGraph().tasks().containsKey("T1"));
        // T1 must be in the deferred list
        List<TaskNode> deferred = extender.deferredToNextTick();
        assertEquals(1, deferred.size());
        assertEquals("T1", deferred.get(0).taskId());
    }

    @Test
    void exceedingMaxMicroStepsThrows() {
        TaskNode t0 = writeTask("T0", 0);
        TaskGraph initial = DagBuilder.build(List.of(t0));

        // Generator keeps creating fresh tasks, would loop forever
        int[] counter = {0};
        MicroStepExtender extender = new MicroStepExtender(task -> {
            counter[0]++;
            String id = "T_gen_" + counter[0];
            // Each generated task writes to a unique position to avoid deferred path
            return List.of(writeTask(id, 1000 + counter[0]));
        }, 5); // low cap for test
        extender.seed(initial);

        assertThrows(MicroStepLimitException.class, () -> {
            // Keep extending until the cap is hit
            for (int i = 0; i < 10; i++) {
                List<String> ids = List.copyOf(extender.currentGraph().tasks().keySet());
                if (!extender.extend(ids)) break;
            }
        });
    }

    @Test
    void alreadyKnownTasksNotAddedAgain() {
        TaskNode t0 = writeTask("T0", 0);
        TaskNode t1 = readWriteTask("T1", 0, 1);
        TaskGraph initial = DagBuilder.build(List.of(t0, t1));

        // Generator always returns T1, which is already in the graph
        MicroStepExtender extender = new MicroStepExtender(task -> List.of(t1), 256);
        extender.seed(initial);

        boolean extended = extender.extend(List.of("T0"));
        assertFalse(extended, "T1 already in graph → no new tasks → fixed point");
        assertEquals(2, extender.currentGraph().tasks().size());
    }
}
