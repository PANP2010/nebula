package org.nebula.replay;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TickPipeline;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Bridges {@link ReplayScheduler} with {@link TickPipeline} for determinism verification
 * (arch doc §12.1).
 *
 * <p>Concrete implementations must provide:
 * <ul>
 *   <li>{@link #inputToTasks} — converts replay inputs to dirty task sets</li>
 *   <li>{@link #computeStateHash} — serialises world state for hash comparison</li>
 * </ul>
 *
 * <p>The pipeline execution itself is handled by this base class: for each tick,
 * it converts inputs → tasks, executes through TickPipeline, and carries deferred
 * tasks forward.
 */
public abstract class TickPipelineReplayAdapter implements ReplayScheduler {

    private final TickPipeline pipeline;
    private long currentTick;
    private List<TaskNode> deferredFromPreviousTick = List.of();

    protected TickPipelineReplayAdapter(TickPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.currentTick = 0;
    }

    @Override
    public void applyInputs(long tickNumber, TickInput input) {
        this.currentTick = tickNumber;

        // Convert player inputs into dirty tasks
        Collection<TaskNode> dirtyTasks = inputToTasks(tickNumber, input);

        // Merge with deferred tasks from the previous tick
        List<TaskNode> allTasks = new java.util.ArrayList<>(dirtyTasks);
        allTasks.addAll(deferredFromPreviousTick);

        // Execute the tick through the DAG pipeline
        try {
            TickPipeline.TickResult result = pipeline.execute(allTasks);
            deferredFromPreviousTick = result.deferredToNextTick();
            onTickCompleted(tickNumber, result);
        } catch (Exception e) {
            onTickFailed(tickNumber, e);
        }
    }

    @Override
    public long currentTick() {
        return currentTick;
    }

    /**
     * Converts replay tick inputs into the dirty task set for this tick.
     * Implementations map player packets → game events → task nodes with RW-sets.
     *
     * @param tickNumber current tick
     * @param input      recorded player inputs
     * @return task nodes to schedule
     */
    protected abstract Collection<TaskNode> inputToTasks(long tickNumber, TickInput input);

    /**
     * Called after each tick completes successfully.
     * Implementations should commit state changes so {@link #computeStateHash()} reflects them.
     */
    protected void onTickCompleted(long tickNumber, TickPipeline.TickResult result) {
        // default no-op; subclasses override to apply state
    }

    /**
     * Called when tick execution fails.
     * Default throws; subclasses may log and continue for resilience testing.
     */
    protected void onTickFailed(long tickNumber, Exception e) {
        throw new RuntimeException("Tick " + tickNumber + " failed", e);
    }
}
