package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidTaskRunnerGuardSeamTest {

    @Test
    void guardBracketsTheResolvedActionExactlyOnce() throws Exception {
        WorldPos pos = new WorldPos(0, 1, 64, 1);
        FluidSnapshot fluid = FluidSnapshot.water(pos, 0, true);
        FluidState state = new FluidState();
        state.put(pos, fluid);
        List<String> calls = new ArrayList<>();
        FluidTaskGuardHook guard = new FluidTaskGuardHook() {
            @Override
            public void beforeTask(TaskNode task) {
                calls.add("before:" + task.taskId());
            }

            @Override
            public void afterTask(TaskNode task) {
                calls.add("after:" + task.taskId());
            }
        };
        FluidTaskRunner runner = new FluidTaskRunner(state,
            id -> ctx -> calls.add("action:" + id), null, guard);
        TaskNode task = FluidTaskFactory.flowInert(fluid);

        runner.run(task);

        assertEquals(List.of("before:" + task.taskId(), "action:" + task.taskId(),
            "after:" + task.taskId()), calls);
    }

    @Test
    void guardAfterRunsWhenActionThrows() {
        WorldPos pos = new WorldPos(0, 1, 64, 1);
        FluidSnapshot fluid = FluidSnapshot.water(pos, 0, true);
        List<String> calls = new ArrayList<>();
        FluidTaskRunner runner = new FluidTaskRunner(new FluidState(),
            id -> ctx -> { throw new IllegalStateException("boom"); }, null,
            new FluidTaskGuardHook() {
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
            runner.run(FluidTaskFactory.flowInert(fluid));
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalStateException);
        }

        assertEquals(List.of("before", "after"), calls);
    }
}
