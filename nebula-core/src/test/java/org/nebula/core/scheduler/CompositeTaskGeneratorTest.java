package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompositeTaskGeneratorTest {

    private static TaskNode dummyTask(String id) {
        return new TaskNode(id, "TEST", RWSet.empty(), () -> {});
    }

    @Test
    void dispatchesToMultipleGenerators() {
        TaskGenerator genA = completed -> {
            if (completed.taskId().equals("trigger")) {
                return List.of(dummyTask("from-A"));
            }
            return List.of();
        };
        TaskGenerator genB = completed -> {
            if (completed.taskId().equals("trigger")) {
                return List.of(dummyTask("from-B"));
            }
            return List.of();
        };

        CompositeTaskGenerator composite = new CompositeTaskGenerator(genA, genB);
        List<TaskNode> result = composite.generateFrom(dummyTask("trigger"));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(t -> t.taskId().equals("from-A")));
        assertTrue(result.stream().anyMatch(t -> t.taskId().equals("from-B")));
    }

    @Test
    void returnsEmptyWhenNoGeneratorProduces() {
        TaskGenerator gen = completed -> List.of();
        CompositeTaskGenerator composite = new CompositeTaskGenerator(gen);

        List<TaskNode> result = composite.generateFrom(dummyTask("noop"));
        assertTrue(result.isEmpty());
    }

    @Test
    void singleGeneratorReturnsDirectly() {
        TaskGenerator gen = completed -> List.of(dummyTask("child"));
        CompositeTaskGenerator composite = new CompositeTaskGenerator(gen);

        List<TaskNode> result = composite.generateFrom(dummyTask("parent"));
        assertEquals(1, result.size());
        assertEquals("child", result.get(0).taskId());
    }

    @Test
    void generatorsCalledInOrder() {
        AtomicInteger order = new AtomicInteger(0);
        TaskGenerator first = completed -> {
            assertEquals(0, order.getAndIncrement());
            return List.of();
        };
        TaskGenerator second = completed -> {
            assertEquals(1, order.getAndIncrement());
            return List.of();
        };

        new CompositeTaskGenerator(first, second).generateFrom(dummyTask("x"));
        assertEquals(2, order.get());
    }
}
