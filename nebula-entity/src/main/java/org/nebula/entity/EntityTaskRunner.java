package org.nebula.entity;

import org.nebula.core.scheduler.CompoundTask;
import org.nebula.core.scheduler.DeterministicOrdering;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link TaskRunner} that executes entity physics tasks against a shared
 * {@link EntityPhysicsState}. Mirrors {@code RedstoneTaskRunner}.
 *
 * <p>Each task gets its own {@link EntityStateSnapshot}; after a layer
 * completes the caller invokes {@link #commitLayer()} to CAS-commit all
 * pending writes, retrying stale-read losers.
 *
 * <p>Actions are resolved per task by an {@code actionResolver}: given a
 * task ID it returns the live {@link EntityTaskAction}, or {@code null} for a
 * no-op (pure-read tasks like COLLISION need no action). SCC-contracted
 * compound tasks dispatch each member through the resolver in deterministic
 * order, so live behaviour survives contraction (the same fix applied to the
 * redstone runner).
 */
public final class EntityTaskRunner implements TaskRunner {

    private static final Logger LOG = Logger.getLogger(EntityTaskRunner.class.getName());

    private final EntityPhysicsState state;
    private final Function<String, EntityTaskAction> actionResolver;
    private final ConcurrentHashMap<String, EntityStateSnapshot> layerSnapshots = new ConcurrentHashMap<>();

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver) {
        this.state = state;
        this.actionResolver = actionResolver != null ? actionResolver : id -> null;
    }

    @Override
    public void run(TaskNode task) throws Exception {
        if (CompoundTask.isCompound(task)) {
            runCompound(task);
            return;
        }
        runMember(task.taskId());
    }

    private void runCompound(TaskNode compound) throws Exception {
        List<String> memberIds = new ArrayList<>(CompoundTask.memberIds(compound));
        memberIds.sort(DeterministicOrdering::compareTaskIds);
        for (String memberId : memberIds) {
            runMember(memberId);
        }
    }

    private void runMember(String taskId) throws Exception {
        EntityTaskAction action = actionResolver.apply(taskId);
        if (action == null) {
            return; // pure-read or unmodelled task — no state mutation
        }
        EntityStateSnapshot snapshot = new EntityStateSnapshot();
        action.execute(new EntityTaskContext(state, snapshot));
        if (!snapshot.isEmpty()) {
            layerSnapshots.put(taskId, snapshot);
        }
    }

    /**
     * Commits all snapshots accumulated in the current layer.
     *
     * @return task IDs whose commits failed (stale reads); empty if all succeeded
     */
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            EntityStateSnapshot snapshot = entry.getValue();
            if (snapshot.isEmpty()) continue;
            EntityStateSnapshot.CommitResult result = snapshot.commit(state);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for entity task " + entry.getKey()
                    + " at fields: " + result.failedFields().keySet());
            }
        }
        return failed;
    }

    public void resetLayer() {
        layerSnapshots.clear();
    }

    public EntityPhysicsState state() {
        return state;
    }
}
