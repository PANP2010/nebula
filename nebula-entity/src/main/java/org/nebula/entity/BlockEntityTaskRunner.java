package org.nebula.entity;

import org.nebula.core.scheduler.CompoundTask;
import org.nebula.core.scheduler.DeterministicOrdering;
import org.nebula.core.scheduler.LayerCommitting;
import org.nebula.core.scheduler.TaskNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link LayerCommitting} runner for block-entity tick tasks, executing
 * against a shared {@link BlockEntityState}. Mirrors {@code EntityTaskRunner} /
 * {@code RedstoneTaskRunner}.
 *
 * <p>Each task gets its own {@link BlockEntitySnapshotState}; after a layer
 * completes, {@link #commitLayer()} CAS-commits all buffered writes and returns
 * the stale-read losers. SCC-contracted compounds (e.g. a chain of hoppers
 * feeding each other) dispatch each member through the resolver in deterministic
 * order, so live behaviour survives contraction.
 */
public final class BlockEntityTaskRunner implements LayerCommitting {

    private static final Logger LOG = Logger.getLogger(BlockEntityTaskRunner.class.getName());

    private final BlockEntityState state;
    private final Function<String, BlockEntityAction> actionResolver;
    private final ConcurrentHashMap<String, BlockEntitySnapshotState> layerSnapshots = new ConcurrentHashMap<>();

    public BlockEntityTaskRunner(BlockEntityState state, Function<String, BlockEntityAction> actionResolver) {
        this.state = state;
        this.actionResolver = actionResolver != null ? actionResolver : id -> null;
    }

    /**
     * Builds a runner whose action resolver is the canonical
     * {@code taskId → snapshot → action} composition (B8 C3).
     *
     * <p>Why this composition needs a snapshot lookup, not just the task ID: a
     * block-entity {@code taskId} is {@code TYPE@dim:x,y,z} — it carries the type and
     * position but <em>not</em> the hopper's facing/output direction or slot count,
     * all of which {@link BlockEntityActionResolver#resolve} needs (see that class's
     * javadoc). So unlike the entity runner (which re-decodes an ENTITY_MOVE action
     * straight from its ID), the block-entity action must be resolved from the
     * {@link BlockEntitySnapshot} the tick hook already accumulated. This factory keeps
     * that composition in ONE place so the live plugin wiring and the unit tests share
     * it — a diverging copy would be exactly the silent-mismatch wound (B3) this
     * subsystem keeps re-learning.
     *
     * @param state       the shared CAS store
     * @param snapshotById maps a live task ID back to the snapshot that seeded it
     *                     (the plugin backs this with the per-tick snapshot registry
     *                     the hook's {@code TaskResolver} populates); a {@code null}
     *                     lookup result resolves to a no-op action
     */
    public static BlockEntityTaskRunner withSnapshotResolver(
            BlockEntityState state, Function<String, BlockEntitySnapshot> snapshotById) {
        Function<String, BlockEntitySnapshot> lookup =
            snapshotById != null ? snapshotById : id -> null;
        return new BlockEntityTaskRunner(state,
            taskId -> BlockEntityActionResolver.resolve(lookup.apply(taskId)));
    }

    @Override
    public void run(TaskNode task) throws Exception {
        if (CompoundTask.isCompound(task)) {
            List<String> memberIds = new ArrayList<>(CompoundTask.memberIds(task));
            memberIds.sort(DeterministicOrdering::compareTaskIds);
            for (String memberId : memberIds) {
                runMember(memberId);
            }
            return;
        }
        runMember(task.taskId());
    }

    private void runMember(String taskId) throws Exception {
        BlockEntityAction action = actionResolver.apply(taskId);
        if (action == null) {
            return;
        }
        BlockEntitySnapshotState snapshot = new BlockEntitySnapshotState();
        action.execute(new BlockEntityContext(state, snapshot));
        if (!snapshot.isEmpty()) {
            layerSnapshots.put(taskId, snapshot);
        }
    }

    @Override
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            BlockEntitySnapshotState snapshot = entry.getValue();
            if (snapshot.isEmpty()) continue;
            BlockEntitySnapshotState.CommitResult result = snapshot.commit(state);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for block-entity task " + entry.getKey()
                    + " at fields: " + result.failedFields().keySet());
            }
        }
        return failed;
    }

    @Override
    public void resetLayer() {
        layerSnapshots.clear();
    }

    public BlockEntityState state() {
        return state;
    }
}
