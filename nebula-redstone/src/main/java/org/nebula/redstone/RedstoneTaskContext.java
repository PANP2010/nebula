package org.nebula.redstone;

import org.nebula.core.state.WorldPos;

/**
 * Execution context passed to redstone task actions. Provides access to
 * the {@link RedstoneWorldState} through a per-task {@link RedstoneStateSnapshot},
 * ensuring all reads are versioned and all writes are buffered until commit.
 */
public final class RedstoneTaskContext {

    private final RedstoneWorldState world;
    private final RedstoneStateSnapshot snapshot;
    private final WorldPos taskPosition;
    private final RedstoneAccessTracer tracer;

    RedstoneTaskContext(RedstoneWorldState world, RedstoneStateSnapshot snapshot, WorldPos taskPosition) {
        this(world, snapshot, taskPosition, null);
    }

    RedstoneTaskContext(RedstoneWorldState world, RedstoneStateSnapshot snapshot,
                        WorldPos taskPosition, RedstoneAccessTracer tracer) {
        this.world = world;
        this.snapshot = snapshot;
        this.taskPosition = taskPosition;
        this.tracer = tracer;
    }

    public int readPowerLevel(WorldPos pos) {
        if (tracer != null) tracer.onBlockRead(pos);
        return snapshot.readPowerLevel(world, pos);
    }

    public Object readInternalState(WorldPos pos, String key) {
        if (tracer != null) tracer.onBlockRead(pos);
        return snapshot.readInternalState(world, pos, key);
    }

    public void writePowerLevel(WorldPos pos, int level) {
        if (tracer != null) tracer.onBlockWrite(pos);
        snapshot.setPowerLevel(pos, level);
    }

    public void writeInternalState(WorldPos pos, String key, Object value) {
        if (tracer != null) tracer.onBlockWrite(pos);
        snapshot.setInternalState(pos, key, value);
    }

    public WorldPos position() {
        return taskPosition;
    }

    RedstoneStateSnapshot snapshot() {
        return snapshot;
    }
}
