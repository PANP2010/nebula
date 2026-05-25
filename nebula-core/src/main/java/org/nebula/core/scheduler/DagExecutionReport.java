package org.nebula.core.scheduler;

import java.util.List;

public record DagExecutionReport(
    int layerCount,
    int taskCount,
    List<List<String>> layers,
    List<String> completedTaskIds
) {
    public DagExecutionReport {
        layers = layers.stream().map(List::copyOf).toList();
        completedTaskIds = List.copyOf(completedTaskIds);
    }
}
