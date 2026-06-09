package org.nebula.redstone;

import org.nebula.core.scheduler.CompoundTask;
import org.nebula.core.scheduler.DeterministicOrdering;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A {@link TaskRunner} that provides each redstone task with a
 * {@link RedstoneTaskContext} bound to the shared {@link RedstoneWorldState}.
 *
 * <p>Each task gets its own {@link RedstoneStateSnapshot}. After all tasks in a
 * layer complete, the caller invokes {@link #commitLayer()} to CAS-commit all
 * pending writes. If any commit fails (stale read), the failed tasks are
 * returned for retry.
 *
 * <p>Usage:
 * <pre>{@code
 * RedstoneTaskRunner runner = new RedstoneTaskRunner(worldState, actionRegistry);
 * // execute all tasks in layer via runner.run(task)
 * List<TaskNode> failed = runner.commitLayer();
 * runner.resetLayer(); // prepare for next layer
 * }</pre>
 */
public final class RedstoneTaskRunner implements TaskRunner {

    private static final Logger LOG = Logger.getLogger(RedstoneTaskRunner.class.getName());

    private final RedstoneWorldState world;
    private final Map<String, RedstoneTaskAction> actionRegistry;
    private final ConcurrentHashMap<String, RedstoneStateSnapshot> layerSnapshots = new ConcurrentHashMap<>();
    private final RedstoneAccessTracer tracer;

    /**
     * @param world          the shared mutable state
     * @param actionRegistry maps task type to the logic that executes with a context.
     *                       If a task type is not found, the task's built-in action runs
     *                       without context (backward-compatible fallback).
     */
    public RedstoneTaskRunner(RedstoneWorldState world, Map<String, RedstoneTaskAction> actionRegistry) {
        this(world, actionRegistry, null);
    }

    public RedstoneTaskRunner(RedstoneWorldState world, Map<String, RedstoneTaskAction> actionRegistry,
                              RedstoneAccessTracer tracer) {
        this.world = world;
        this.actionRegistry = actionRegistry != null ? actionRegistry : Map.of();
        this.tracer = tracer;
    }

    public RedstoneTaskRunner(RedstoneWorldState world) {
        this(world, Map.of(), null);
    }

    @Override
    public void run(TaskNode task) throws Exception {
        // SCC contraction merges mutually-dependent redstone components (e.g. a
        // wire line, which forms RAW cycles on neighbouring blocks plus a shared
        // region-signal global) into a single COMPOUND_SCC task. Dispatch each
        // member through the action registry in deterministic order so live
        // behaviour survives contraction; otherwise the compound's built-in
        // action (inert member no-ops) would run and the simulation would stall.
        if (CompoundTask.isCompound(task)) {
            runCompound(task);
            return;
        }

        RedstoneTaskAction action = actionRegistry.get(task.taskType());
        if (action == null) {
            // Fallback: run the task's built-in action without context
            task.action().execute();
            return;
        }

        runMember(task.taskId(), action);
    }

    private void runCompound(TaskNode compound) throws Exception {
        List<String> memberIds = new ArrayList<>(CompoundTask.memberIds(compound));
        memberIds.sort(DeterministicOrdering::compareTaskIds);

        boolean ranAny = false;
        for (String memberId : memberIds) {
            String taskType = typeOf(memberId);
            RedstoneTaskAction action = taskType == null ? null : actionRegistry.get(taskType);
            if (action == null) {
                continue; // member type has no live action; nothing to do
            }
            runMember(memberId, action);
            ranAny = true;
        }

        if (!ranAny) {
            // No member had a registered action — preserve the compound's own
            // built-in behaviour as a fallback.
            compound.action().execute();
        }
    }

    private void runMember(String taskId, RedstoneTaskAction action) throws Exception {
        WorldPos pos = RedstoneTaskGenerator.parsePosition(taskId);
        RedstoneStateSnapshot snapshot = new RedstoneStateSnapshot();
        RedstoneTaskContext context = new RedstoneTaskContext(world, snapshot, pos, tracer);

        action.execute(context);

        layerSnapshots.put(taskId, snapshot);
    }

    /** Extracts the task-type prefix from a redstone task ID ({@code TYPE@dim:x,y,z}). */
    private static String typeOf(String taskId) {
        int at = taskId.indexOf('@');
        return at < 0 ? null : taskId.substring(0, at);
    }

    /**
     * Commits all snapshots accumulated in the current layer.
     *
     * @return list of task IDs whose commits failed (stale reads detected).
     *         Empty if all succeeded.
     */
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            RedstoneStateSnapshot snapshot = entry.getValue();
            if (snapshot.isEmpty()) continue;

            RedstoneStateSnapshot.CommitResult result = snapshot.commit(world);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for task " + entry.getKey()
                    + " at positions: " + result.failedPositions().keySet());
            }
        }
        return failed;
    }

    /**
     * Resets the layer snapshot collection for the next layer's execution.
     */
    public void resetLayer() {
        layerSnapshots.clear();
    }

    /**
     * Returns the snapshot for a specific task (for testing/inspection).
     */
    public RedstoneStateSnapshot getSnapshot(String taskId) {
        return layerSnapshots.get(taskId);
    }

    public RedstoneWorldState world() {
        return world;
    }
}
