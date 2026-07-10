package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.FluidAccessTracer;
import org.nebula.guard.ThreadLocalAccessTrace;

/** Bridges pure fluid block accesses into the RW guard trace (B8 C4). */
public final class FluidRwGuardTracer implements FluidAccessTracer {

    public static final FluidRwGuardTracer INSTANCE = new FluidRwGuardTracer();

    private FluidRwGuardTracer() {}

    @Override
    public void onBlockRead(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockRead(pos);
    }

    @Override
    public void onBlockWrite(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockWrite(pos);
    }
}
