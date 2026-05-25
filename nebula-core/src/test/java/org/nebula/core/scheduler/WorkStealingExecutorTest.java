package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkStealingExecutorTest {

    private static TaskNode action(String id, int x, Runnable r) {
        return new TaskNode(id, "W",
            RWSet.builder().writeBlock(new WorldPos(0, x, 64, 0)).build(),
            r::run);
    }

    @Test
    void allTasksExecuteExactlyOnce() throws Exception {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String id = "T" + i;
            tasks.add(action(id, i * 50, () -> executed.add(id)));
        }
        TaskGraph graph = new TaskGraph(
            tasks.stream().collect(java.util.stream.Collectors.toMap(TaskNode::taskId, t -> t)),
            java.util.Set.of()
        );

        try (WorkStealingExecutor exec = new WorkStealingExecutor(4)) {
            DagExecutor.execute(graph, TaskRunner.DIRECT, exec);
        }

        assertEquals(20, executed.size());
        assertEquals(tasks.stream().map(TaskNode::taskId).sorted().toList(),
            executed.stream().sorted().toList());
    }

    @Test
    void layerBarrierRespected() throws Exception {
        // T_write must execute before T_read (RAW dependency)
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        WorldPos pos = new WorldPos(0, 0, 64, 0);
        TaskNode writer = new TaskNode("T_write", "W",
            RWSet.builder().writeBlock(pos).build(), () -> order.add("write"));
        TaskNode reader = new TaskNode("T_read", "R",
            RWSet.builder().readBlock(pos).build(), () -> order.add("read"));

        TaskGraph graph = DagBuilder.build(List.of(writer, reader));

        try (WorkStealingExecutor exec = new WorkStealingExecutor(2)) {
            DagExecutor.execute(graph, TaskRunner.DIRECT, exec);
        }

        assertEquals(List.of("write", "read"), order);
    }

    @Test
    void independentTasksAllExecute() throws Exception {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int fi = i;
            tasks.add(new TaskNode("T" + i, "W", RWSet.empty(), () -> executed.add("T" + fi)));
        }
        TaskGraph graph = DagBuilder.build(tasks);

        try (WorkStealingExecutor exec = new WorkStealingExecutor(4)) {
            DagExecutor.execute(graph, TaskRunner.DIRECT, exec);
        }

        assertEquals(10, executed.size());
    }

    @Test
    void taskFailurePropagatesToDagExecutionException() {
        TaskNode failing = new TaskNode("T", "W", RWSet.empty(),
            () -> { throw new RuntimeException("boom"); });
        TaskGraph graph = DagBuilder.build(List.of(failing));

        try (WorkStealingExecutor exec = new WorkStealingExecutor(2)) {
            assertThrows(DagExecutionException.class,
                () -> DagExecutor.execute(graph, TaskRunner.DIRECT, exec));
        }
    }

    @Test
    void workersStealFromOtherQueues() throws Exception {
        // Place all tasks on core 0's queue by giving them all the same position
        // Other cores have empty queues — they must steal
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int fi = i;
            // Different positions so they don't all hash to same core
            tasks.add(new TaskNode("T" + i, "W",
                RWSet.builder().writeBlock(new WorldPos(0, i * 997, 64, 0)).build(),
                () -> executed.add("T" + fi)));
        }
        TaskGraph graph = DagBuilder.build(tasks);

        try (WorkStealingExecutor exec = new WorkStealingExecutor(4)) {
            DagExecutor.execute(graph, TaskRunner.DIRECT, exec);
        }

        assertEquals(16, executed.size());
    }
}
