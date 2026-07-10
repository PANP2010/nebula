package org.nebula.entity;

import org.nebula.core.scheduler.TaskNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Executes fluid actions against per-task snapshots and commits their buffered block writes. */
public final class FluidTaskRunner {

    private final FluidState state;
    private final Function<String, FluidAction> actionResolver;
    private final FluidAccessTracer tracer;
    private final FluidTaskGuardHook guardHook;
    private final Map<String, FluidStateSnapshot> snapshots = new ConcurrentHashMap<>();

    public FluidTaskRunner(FluidState state, Function<String, FluidAction> actionResolver,
                           FluidAccessTracer tracer) {
        this(state, actionResolver, tracer, null);
    }

    public FluidTaskRunner(FluidState state, Function<String, FluidAction> actionResolver,
                           FluidAccessTracer tracer, FluidTaskGuardHook guardHook) {
        this.state = state;
        this.actionResolver = actionResolver;
        this.tracer = tracer;
        this.guardHook = guardHook;
    }

    public void run(TaskNode task) throws Exception {
        if (guardHook != null) {
            guardHook.beforeTask(task);
        }
        try {
            FluidAction action = actionResolver.apply(task.taskId());
            if (action == null) return;
            FluidStateSnapshot snapshot = new FluidStateSnapshot();
            action.execute(new FluidContext(state, snapshot, tracer));
            snapshots.put(task.taskId(), snapshot);
        } finally {
            if (guardHook != null) {
                guardHook.afterTask(task);
            }
        }
    }

    public boolean commit(String taskId) {
        FluidStateSnapshot snapshot = snapshots.remove(taskId);
        return snapshot == null || snapshot.commit(state);
    }
}
