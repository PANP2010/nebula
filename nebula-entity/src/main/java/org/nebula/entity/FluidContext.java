package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/** Execution context that makes every fluid block access traceable and versioned. */
public final class FluidContext {

    private final FluidState state;
    private final FluidStateSnapshot snapshot;
    private final FluidAccessTracer tracer;

    FluidContext(FluidState state, FluidStateSnapshot snapshot, FluidAccessTracer tracer) {
        this.state = state;
        this.snapshot = snapshot;
        this.tracer = tracer;
    }

    public Object readBlock(WorldPos pos) {
        if (tracer != null) tracer.onBlockRead(pos);
        return snapshot.read(state, pos);
    }

    public void writeBlock(WorldPos pos, Object value) {
        if (tracer != null) tracer.onBlockWrite(pos);
        snapshot.write(pos, value);
    }
}
