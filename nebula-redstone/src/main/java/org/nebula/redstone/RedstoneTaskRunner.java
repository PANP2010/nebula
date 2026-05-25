package org.nebula.redstone;

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
        RedstoneTaskAction action = actionRegistry.get(task.taskType());
        if (action == null) {
            // Fallback: run the task's built-in action without context
            task.action().execute();
            return;
        }

        WorldPos pos = RedstoneTaskGenerator.parsePosition(task.taskId());
        RedstoneStateSnapshot snapshot = new RedstoneStateSnapshot();
        RedstoneTaskContext context = new RedstoneTaskContext(world, snapshot, pos, tracer);

        action.execute(context);

        layerSnapshots.put(task.taskId(), snapshot);
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
