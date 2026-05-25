package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RWConflictDetectorTest {
    @Test
    void detectsRawAndWawConflicts() {
        WorldPos pos = new WorldPos(0, 15, 64, 32);
        EntityField health = new EntityField(42L, "health");

        TaskNode writer = TaskNode.inert("writer", "WRITE_BLOCK", RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .writeEntity(health)
            .build());
        TaskNode readerWriter = TaskNode.inert("reader-writer", "READ_WRITE_BLOCK", RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .readEntity(health)
            .build());

        List<DependencyEdge> edges = RWConflictDetector.edgesFor(writer, readerWriter);

        // RAW: writer writes pos, readerWriter reads pos → writer→readerWriter
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == DependencyType.RAW
            && edge.sourceTaskId().equals("writer")));
        // WAW: both write pos
        assertTrue(edges.stream().anyMatch(edge -> edge.type() == DependencyType.WAW));
        // WAR is removed — covered by write-buffer snapshot mechanism
    }

    @Test
    void wawOrderingIsStableAcrossRuns() {
        WorldPos pos = new WorldPos(0, 1, 2, 3);
        TaskNode left = TaskNode.inert("left", "WRITE", RWSet.builder().writeBlock(pos).build());
        TaskNode right = TaskNode.inert("right", "WRITE", RWSet.builder().writeBlock(pos).build());

        DependencyEdge first = RWConflictDetector.edgesFor(left, right).stream()
            .filter(edge -> edge.type() == DependencyType.WAW)
            .findFirst()
            .orElseThrow();
        DependencyEdge second = RWConflictDetector.edgesFor(left, right).stream()
            .filter(edge -> edge.type() == DependencyType.WAW)
            .findFirst()
            .orElseThrow();

        assertEquals(first, second);
    }
}
