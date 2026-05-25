package org.nebula.core.scheduler;

public record DependencyEdge(String sourceTaskId, String targetTaskId, DependencyType type) {
}
