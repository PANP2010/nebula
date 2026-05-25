package org.nebula.core.scheduler;

import org.nebula.core.state.WorldPos;

import java.util.Set;

/**
 * Maps tasks to CPU core indices based on their spatial position (arch doc §4.4).
 *
 * <p>The dispatch rule is:
 * <ul>
 *   <li>Task has at least one written block → {@code hash(firstWrittenPos) % coreCount}</li>
 *   <li>Task has no written blocks but has read blocks → {@code hash(firstReadPos) % coreCount}</li>
 *   <li>Task has no spatial positions → returns {@link #GLOBAL_CORE} (-1), placed in a shared global queue</li>
 * </ul>
 *
 * <p>The hash uses the WorldPos natural ordering (dim, x, y, z) to ensure that
 * spatially adjacent tasks tend to land on the same core, maximising cache locality.
 */
public final class CoreAffinity {

    /** Sentinel value: task has no spatial position and should go to the global queue. */
    public static final int GLOBAL_CORE = -1;

    private CoreAffinity() {
    }

    /**
     * Returns the preferred core index for {@code task}, in the range {@code [0, coreCount)}
     * or {@link #GLOBAL_CORE} for tasks without spatial positions.
     *
     * @param task      the task to assign
     * @param coreCount number of available worker cores (must be ≥ 1)
     */
    public static int homeCore(TaskNode task, int coreCount) {
        if (coreCount <= 0) {
            throw new IllegalArgumentException("coreCount must be >= 1");
        }

        // Prefer write positions — they are the source of data conflicts
        Set<WorldPos> writes = task.declaredRWSet().writtenBlocks();
        if (!writes.isEmpty()) {
            WorldPos anchor = writes.iterator().next();
            return Math.abs(posHash(anchor)) % coreCount;
        }

        Set<WorldPos> reads = task.declaredRWSet().readBlocks();
        if (!reads.isEmpty()) {
            WorldPos anchor = reads.iterator().next();
            return Math.abs(posHash(anchor)) % coreCount;
        }

        return GLOBAL_CORE;
    }

    /** Stable, deterministic hash of a WorldPos — avoids Object.hashCode() non-determinism. */
    private static int posHash(WorldPos pos) {
        int h = pos.dimensionId();
        h = 31 * h + pos.x();
        h = 31 * h + pos.y();
        h = 31 * h + pos.z();
        // Wang hash mix for better distribution
        h = ((h >>> 16) ^ h) * 0x45d9f3b;
        h = ((h >>> 16) ^ h) * 0x45d9f3b;
        h = (h >>> 16) ^ h;
        return h;
    }
}
