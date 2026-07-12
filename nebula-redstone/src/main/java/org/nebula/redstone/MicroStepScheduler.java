package org.nebula.redstone;

import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.scheduler.BudgetedDagBuilder;
import org.nebula.core.scheduler.DagBuildBudget;
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
    private final BudgetedDagBuilder budgetedBuilder;
    /** Exposes the build-time budget tracker so {@code /nebula dag-stats} can report it. */
    public final DagBuildBudget dagBuildBudget;

    /**
     * Exposes the fast-build phase breakdown tracker so {@code /nebula dag-stats} can
     * report per-phase build costs (sort / partition / conflict / SCC / assemble).
     */
    public org.nebula.core.scheduler.FastBuildStats fastBuildStats() {
        return org.nebula.core.scheduler.FastBuildStats.INSTANCE;
    }

    public MicroStepScheduler(RedstoneTaskGenerator generator, TaskRunner runner) {
        this(generator, runner, new BucketDagBuilder(REDSTONE_BUCKET_SIZE));
    }

    public MicroStepScheduler(RedstoneTaskGenerator generator, TaskRunner runner,
                              BucketDagBuilder bucketBuilder) {
        this.generator = generator;
        this.runner = runner;
        this.budgetedBuilder = new BudgetedDagBuilder(bucketBuilder, new DagBuildBudget());
        this.dagBuildBudget = this.budgetedBuilder.budget();
    }

    /** Constructor accepting a pre-built {@link BudgetedDagBuilder} (for test / plugin injection). */
    public MicroStepScheduler(RedstoneTaskGenerator generator, TaskRunner runner,
                              BudgetedDagBuilder budgetedBuilder) {
        this.generator = generator;
        this.runner = runner;
        this.budgetedBuilder = budgetedBuilder;
        this.dagBuildBudget = budgetedBuilder.budget();
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
            return new TickResult(0, 0, 0, List.of(), List.of(), Set.of());
        }

        List<String> allCompletedIds = new ArrayList<>();
        List<String> commitFailures = new ArrayList<>();
        Set<WorldPos> allModifiedPositions = new LinkedHashSet<>();
        int microSteps = 0;
        int totalLayers = 0;

        // Track power levels before execution for change detection.
        //
        // The runner may be a wrapper (e.g. ParallelTaskRunner fanning the layer
        // across a worker pool) delegating to the real RedstoneTaskRunner that
        // owns the CAS store and the layer commit lifecycle. Resolve through the
        // unwrap() seam so change-detection and commitLayer() still find the
        // committer when parallelism is on — otherwise a wrapped runner would
        // silently skip both (no cascade, no writes).
        RedstoneTaskRunner committer =
            runner.unwrap() instanceof RedstoneTaskRunner rtr ? rtr : null;
        RedstoneWorldState world = committer != null ? committer.world() : null;

        // Current batch of tasks to execute
        List<TaskNode> currentBatch = new ArrayList<>(initialDirtyTasks);
        Set<String> allExecutedIds = new LinkedHashSet<>();

        while (!currentBatch.isEmpty()) {
            if (microSteps > MAX_MICRO_STEPS) {
                throw new DagExecutionException(totalLayers, List.copyOf(allCompletedIds),
                    List.of(new MicroStepLimitException(microSteps,
                        "MAX_MICRO_STEPS=" + MAX_MICRO_STEPS + " exceeded")));
            }

            // Build DAG for this batch using spatial pre-bucketing with budget tracking
            TaskGraph graph = budgetedBuilder.build(currentBatch);
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

                // Execute layer through the runLayer() seam so an intra-layer-
                // parallel TaskRunner (e.g. ParallelTaskRunner) can fan the layer's
                // tasks across a worker pool. Tasks within one topological layer
                // have no read/write conflicts by construction (BucketDagBuilder),
                // so concurrent execution is data-race free. The default serial
                // runLayer (TaskRunner interface) calls run() once per task in order,
                // making this identical to the previous per-task loop — the B9 D5
                // enabler for parallel DAG execution without changing serial behaviour.
                List<TaskNode> layerNodes = new ArrayList<>(layer.size());
                for (String taskId : layer) {
                    if (allExecutedIds.contains(taskId)) continue;
                    TaskNode task = graph.tasks().get(taskId);
                    if (task == null) continue;
                    layerNodes.add(task);
                }
                if (!layerNodes.isEmpty()) {
                    try {
                        runner.runLayer(layerNodes);
                    } catch (Exception e) {
                        throw new DagExecutionException(totalLayers,
                            List.copyOf(allCompletedIds), List.of(e));
                    }
                    for (TaskNode task : layerNodes) {
                        allCompletedIds.add(task.taskId());
                        allExecutedIds.add(task.taskId());
                    }
                }

                // Commit layer with bounded CAS retry. On stale-read failure
                // re-execute only the losers with a fresh snapshot — their new
                // reads will pick up the winning writer's values. The committer
                // is the (possibly unwrapped) RedstoneTaskRunner that buffered
                // this layer's writes; retries go back through the top-level
                // runner so a parallel wrapper still applies to the re-runs.
                if (committer != null) {
                    List<String> failed = committer.commitLayer();
                    int retries = 0;
                    while (!failed.isEmpty() && retries < MAX_CAS_RETRIES) {
                        committer.resetLayer();
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
                        failed = committer.commitLayer();
                        retries++;
                    }
                    if (!failed.isEmpty()) {
                        commitFailures.addAll(failed);
                    }
                    committer.resetLayer();
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
                // Accumulate all modified positions across layers for sync-back
                allModifiedPositions.addAll(changedPositions);

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
            commitFailures,
            Set.copyOf(allModifiedPositions)
        );
    }

    public record TickResult(
        int totalTasks,
        int totalLayers,
        int microSteps,
        List<TaskNode> deferredToNextTick,
        List<String> commitFailures,
        Set<WorldPos> modifiedPositions
    ) {
        public boolean hasCommitFailures() {
            return commitFailures != null && !commitFailures.isEmpty();
        }
    }
}
