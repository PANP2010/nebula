package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.*;

class CoreAffinityTest {

    private static TaskNode writeTask(String id, int x) {
        return TaskNode.inert(id, "W",
            RWSet.builder().writeBlock(new WorldPos(0, x, 64, 0)).build());
    }

    private static TaskNode readTask(String id, int x) {
        return TaskNode.inert(id, "R",
            RWSet.builder().readBlock(new WorldPos(0, x, 64, 0)).build());
    }

    private static TaskNode emptyTask(String id) {
        return TaskNode.inert(id, "EMPTY", RWSet.empty());
    }

    @Test
    void writePosUsedForHomeCore() {
        TaskNode t = writeTask("T", 5);
        int core = CoreAffinity.homeCore(t, 4);
        assertTrue(core >= 0 && core < 4);
    }

    @Test
    void readPosUsedWhenNoWrites() {
        TaskNode t = readTask("T", 10);
        int core = CoreAffinity.homeCore(t, 4);
        assertTrue(core >= 0 && core < 4);
    }

    @Test
    void noPositionReturnsGlobalCore() {
        assertEquals(CoreAffinity.GLOBAL_CORE, CoreAffinity.homeCore(emptyTask("T"), 4));
    }

    @Test
    void deterministic() {
        TaskNode t = writeTask("T", 100);
        int c1 = CoreAffinity.homeCore(t, 8);
        int c2 = CoreAffinity.homeCore(t, 8);
        assertEquals(c1, c2);
    }

    @Test
    void differentPositionsDistribute() {
        // With 4 cores and many positions, we should get at least 2 distinct core assignments
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int x = 0; x < 64; x++) {
            seen.add(CoreAffinity.homeCore(writeTask("T" + x, x * 100), 4));
        }
        assertTrue(seen.size() >= 2, "Expected distribution across cores, got: " + seen);
    }

    @Test
    void singleCoreAlwaysReturnsZero() {
        TaskNode t = writeTask("T", 999);
        assertEquals(0, CoreAffinity.homeCore(t, 1));
    }

    @Test
    void invalidCoreCountThrows() {
        assertThrows(IllegalArgumentException.class, () -> CoreAffinity.homeCore(emptyTask("T"), 0));
        assertThrows(IllegalArgumentException.class, () -> CoreAffinity.homeCore(emptyTask("T"), -1));
    }
}
