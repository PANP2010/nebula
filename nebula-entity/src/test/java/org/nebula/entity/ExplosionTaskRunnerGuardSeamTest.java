package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionTaskRunnerGuardSeamTest {

    private static final WorldPos CENTER = new WorldPos(0, 10, 64, 10);
    private static final WorldPos WEST = new WorldPos(0, 9, 64, 10);
    private static final ExplosionSnapshot EXPLOSION =
        new ExplosionSnapshot(CENTER, 4.0f, -1L, Set.of(WEST), List.of());

    @Test
    void guardBracketsTheResolvedActionExactlyOnce() throws Exception {
        List<String> calls = new ArrayList<>();
        TaskNode task = ExplosionTaskFactory.createSubDag(EXPLOSION).stream()
            .filter(candidate -> candidate.taskType().equals("EXPLOSION_BLOCK_DESTROY"))
            .findFirst().orElseThrow();
        ExplosionTaskRunner runner = new ExplosionTaskRunner(new FluidState(), new EntityPhysicsState(),
            id -> ctx -> calls.add("action:" + id), null,
            new ExplosionTaskGuardHook() {
                @Override
                public void beforeTask(TaskNode task) {
                    calls.add("before:" + task.taskId());
                }

                @Override
                public void afterTask(TaskNode task) {
                    calls.add("after:" + task.taskId());
                }
            });

        runner.run(task);

        assertEquals(List.of("before:" + task.taskId(), "action:" + task.taskId(),
            "after:" + task.taskId()), calls);
    }

    @Test
    void guardAfterRunsWhenActionThrows() {
        List<String> calls = new ArrayList<>();
        TaskNode task = ExplosionTaskFactory.createSubDag(EXPLOSION).stream()
            .filter(candidate -> candidate.taskType().equals("EXPLOSION_BLOCK_DESTROY"))
            .findFirst().orElseThrow();
        ExplosionTaskRunner runner = new ExplosionTaskRunner(new FluidState(), new EntityPhysicsState(),
            id -> ctx -> { throw new IllegalStateException("boom"); }, null,
            new ExplosionTaskGuardHook() {
                @Override
                public void beforeTask(TaskNode task) {
                    calls.add("before");
                }

                @Override
                public void afterTask(TaskNode task) {
                    calls.add("after");
                }
            });

        try {
            runner.run(task);
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalStateException);
        }

        assertEquals(List.of("before", "after"), calls);
    }
}
