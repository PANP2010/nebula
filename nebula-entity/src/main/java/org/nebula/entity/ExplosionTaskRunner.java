package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.RandomUsage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Executes pure explosion actions against versioned block/entity snapshots. */
public final class ExplosionTaskRunner {

    private final FluidState blockState;
    private final EntityPhysicsState entityState;
    private final Function<String, ExplosionAction> actionResolver;
    private final ExplosionAccessTracer tracer;
    private final Map<String, PendingSnapshots> snapshots = new ConcurrentHashMap<>();

    public ExplosionTaskRunner(FluidState blockState, EntityPhysicsState entityState,
                               Function<String, ExplosionAction> actionResolver,
                               ExplosionAccessTracer tracer) {
        this.blockState = blockState;
        this.entityState = entityState;
        this.actionResolver = actionResolver;
        this.tracer = tracer;
    }

    public void run(TaskNode task) throws Exception {
        ExplosionAction action = actionResolver.apply(task.taskId());
        if (action == null) return;
        FluidStateSnapshot blockSnapshot = new FluidStateSnapshot();
        EntityStateSnapshot entitySnapshot = new EntityStateSnapshot();
        RandomUsage usage = task.declaredRWSet().randomUsage().orElse(null);
        DeterministicRandom random = usage == null ? null : new DeterministicRandom(task.taskId().hashCode());
        action.execute(new ExplosionContext(blockState, blockSnapshot, entityState, entitySnapshot,
            random, usage == null ? null : usage.instance(), tracer));
        snapshots.put(task.taskId(), new PendingSnapshots(blockSnapshot, entitySnapshot));
    }

    public boolean commit(String taskId) {
        PendingSnapshots pending = snapshots.remove(taskId);
        if (pending == null) return true;
        if (!pending.blockSnapshot().commit(blockState)) return false;
        return pending.entitySnapshot().commit(entityState).success();
    }

    private record PendingSnapshots(FluidStateSnapshot blockSnapshot,
                                    EntityStateSnapshot entitySnapshot) {}
}
