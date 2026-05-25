package org.nebula.folia.bridge;

import org.nebula.core.scheduler.TaskNode;

public record FoliaTaskContext(long tickNumber, FoliaRegionContext regionContext, TaskNode taskNode) {
    public FoliaTaskContext {
        if (tickNumber < 0) {
            throw new IllegalArgumentException("tickNumber must be non-negative");
        }
        if (regionContext == null) {
            throw new IllegalArgumentException("regionContext must not be null");
        }
        if (taskNode == null) {
            throw new IllegalArgumentException("taskNode must not be null");
        }
    }
}
