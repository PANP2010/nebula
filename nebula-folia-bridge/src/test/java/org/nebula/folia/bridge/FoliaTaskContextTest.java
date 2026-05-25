package org.nebula.folia.bridge;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoliaTaskContextTest {
    @Test
    void storesRegionAndTaskBoundaryData() {
        FoliaRegionContext region = new FoliaRegionContext("world", 3, -2);
        TaskNode task = TaskNode.inert("task", "TYPE", RWSet.empty());

        FoliaTaskContext context = new FoliaTaskContext(42L, region, task);

        assertEquals(42L, context.tickNumber());
        assertEquals(region, context.regionContext());
        assertEquals(task, context.taskNode());
    }
}
