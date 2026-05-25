package org.nebula.core.scheduler;

import java.util.List;

public final class DagExecutionException extends Exception {
    private final int failedLayerIndex;
    private final List<String> completedTaskIds;
    private final List<Throwable> failures;

    public DagExecutionException(int failedLayerIndex, List<String> completedTaskIds, List<Throwable> failures) {
        super("DAG execution failed in layer " + failedLayerIndex + " with " + failures.size() + " failure(s)", firstFailure(failures));
        this.failedLayerIndex = failedLayerIndex;
        this.completedTaskIds = List.copyOf(completedTaskIds);
        this.failures = List.copyOf(failures);
    }

    public int failedLayerIndex() {
        return failedLayerIndex;
    }

    public List<String> completedTaskIds() {
        return completedTaskIds;
    }

    public List<Throwable> failures() {
        return failures;
    }

    private static Throwable firstFailure(List<Throwable> failures) {
        return failures.isEmpty() ? null : failures.getFirst();
    }
}
