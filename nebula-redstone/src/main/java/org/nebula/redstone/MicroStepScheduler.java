package org.nebula.redstone;

import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.MicroStepLimitException;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Orchestrates one tick's redstone execution including microstep propagation
 * (arch doc §5.3).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build the initial DAG from the dirty-set tasks.</li>
 *   <li>Execute layer by layer. After each layer, commit via CAS, then detect
 *       which positions actually changed power level.</li>
 *   <li>Use change-aware generation to create downstream tasks only for
 *       actual signal changes (§5.3 fixed-point detection).</li>
 *   <li>Stop when no more tasks are generated (fixed point) or the microstep
 *       cap is reached.</li>
 * </ol>
 */
public final class MicroStepScheduler {

    private static final Logger LOG = Logger.getLogger(MicroStepScheduler.class.getName());
    public static final int MAX_MICRO_STEPS = 256;

    /**
     * Maximum number of CAS retry rounds per layer before commit failures are
     * reported in {@link TickResult#commitFailures}.  Each retry re-executes
     * only the tasks whose previous commit lost a version race; their fresh
     * snapshot will see the updated world state.
     */
    public static final int MAX_CAS_RETRIES = 3;

    /**
     * Bucket edge length used for redstone DAG construction (arch doc §14.2 Month 3-4
     * "针对红石优化的桶大小"). Set to redstone signal range + 1 (15 + 1 = 16) so a
     * wire's RW-set spans at most two adjacent buckets along any axis, keeping the
     * inter-bucket conflict surface small while preserving correctness.
     */
    public static final int REDSTONE_BUCKET_SIZE = 16;

    private final RedstoneTaskGenerator generator;
    private final TaskRunner runner;
    private final BucketDagBuilder bucketBuilder;

    public MicroStepScheduler(RedstoneTaskGenerator generator, TaskRunner runner) {
        this(generator, runner, new BucketDagBuilder(REDSTONE_BUCKET_SIZE));
    }

    public MicroStepScheduler(RedstoneTaskGenerator generator, TaskRunner runner,
                              BucketDagBuilder bucketBuilder) {
        this.generator = generator;
        this.runner = runner;
        this.bucketBuilder = bucketBuilder;
    }

    /**
     * Executes one tick's redstone tasks, including microstep expansion.
     *
     * @param initialDirtyTasks tasks whose redstone outputs may have changed
     *                          at the start of this tick
     * @return a report of all tasks executed across all microstep layers
     * @throws DagExecutionException if any task fails
     */
    public TickResult executeTick(List<TaskNode> initialDirtyTasks) throws DagExecutionException {
        if (initialDirtyTasks.isEmpty()) {
            return new TickResult(0, 0, 0, List.of(), List.of());
        }

        List<String> allCompletedIds = new ArrayList<>();
        List<String> commitFailures = new ArrayList<>();
        int microSteps = 0;
        int totalLayers = 0;

        // Track power levels before execution for change detection
        RedstoneWorldState world = null;
        if (runner instanceof RedstoneTaskRunner rtr) {
            world = rtr.world();
        }

        // Current batch of tasks to execute
        List<TaskNode> currentBatch = new ArrayList<>(initialDirtyTasks);
        Set<String> allExecutedIds = new LinkedHashSet<>();

        while (!currentBatch.isEmpty()) {
            if (microSteps > MAX_MICRO_STEPS) {
                throw new DagExecutionException(totalLayers, List.copyOf(allCompletedIds),
                    List.of(new MicroStepLimitException(microSteps,
                        "MAX_MICRO_STEPS=" + MAX_MICRO_STEPS + " exceeded")));
            }

            // Build DAG for this batch using spatial pre-bucketing
            TaskGraph graph = bucketBuilder.build(currentBatch);
            List<List<String>> layers = graph.topologicalLayers();

            // Track positions written in this batch for change detection
            Set<WorldPos> changedPositions = new LinkedHashSet<>();

            for (List<String> layer : layers) {
                // Snapshot power levels before execution (for change detection)
                Map<WorldPos, Integer> powerBefore = new java.util.LinkedHashMap<>();
                if (world != null) {
                    for (String taskId : layer) {
                        WorldPos pos = RedstoneTaskGenerator.parsePosition(taskId);
                        if (pos != null) {
                            powerBefore.put(pos, world.getPowerLevel(pos));
                        }
                    }
                }

                // Execute layer
                for (String taskId : layer) {
                    if (allExecutedIds.contains(taskId)) continue;
                    TaskNode task = graph.tasks().get(taskId);
                    if (task == null) continue;
                    try {
                        runner.run(task);
                    } catch (Exception e) {
                        throw new DagExecutionException(totalLayers,
                            List.copyOf(allCompletedIds), List.of(e));
                    }
                    allCompletedIds.add(taskId);
                    allExecutedIds.add(taskId);
                }

                // Commit layer with bounded CAS retry. On stale-read failure
                // re-execute only the losers with a fresh snapshot — their new
                // reads will pick up the winning writer's values.
                if (runner instanceof RedstoneTaskRunner rtr) {
                    List<String> failed = rtr.commitLayer();
                    int retries = 0;
                    while (!failed.isEmpty() && retries < MAX_CAS_RETRIES) {
                        rtr.resetLayer();
                        for (String taskId : failed) {
                            TaskNode task = graph.tasks().get(taskId);
                            if (task == null) continue;
                            try {
                                runner.run(task);
                            } catch (Exception e) {
                                throw new DagExecutionException(totalLayers,
                                    List.copyOf(allCompletedIds), List.of(e));
                            }
                        }
                        failed = rtr.commitLayer();
                        retries++;
                    }
                    if (!failed.isEmpty()) {
                        commitFailures.addAll(failed);
                    }
                    rtr.resetLayer();
                }

                // Detect actual power changes
                if (world != null) {
                    for (var entry : powerBefore.entrySet()) {
                        int after = world.getPowerLevel(entry.getKey());
                        if (after != entry.getValue()) {
                            changedPositions.add(entry.getKey());
                        }
                    }
                }

                totalLayers++;
            }

            // Microstep: generate downstream tasks from actual changes
            if (changedPositions.isEmpty()) {
                break; // Fixed point — no signal changes
            }

            List<TaskNode> nextBatch = generator.generateFromChanges(changedPositions);
            // Filter out tasks we've already executed
            nextBatch = nextBatch.stream()
                .filter(t -> !allExecutedIds.contains(t.taskId()))
                .toList();

            if (nextBatch.isEmpty()) {
                break; // No new work
            }

            microSteps++;
            currentBatch = nextBatch;
        }

        if (microSteps > 0) {
            int finalMicroSteps = microSteps;
            int finalTasks = allCompletedIds.size();
            LOG.fine(() -> "Tick used " + finalMicroSteps + " microstep(s), "
                + finalTasks + " tasks total");
        }

        return new TickResult(
            allCompletedIds.size(),
            totalLayers,
            microSteps,
            List.of(), // deferred tasks (future: from DEFERRED components)
            commitFailures
        );
    }

    public record TickResult(
        int totalTasks,
        int totalLayers,
        int microSteps,
        List<TaskNode> deferredToNextTick,
        List<String> commitFailures
    ) {
        public boolean hasCommitFailures() {
            return commitFailures != null && !commitFailures.isEmpty();
        }
    }
}
