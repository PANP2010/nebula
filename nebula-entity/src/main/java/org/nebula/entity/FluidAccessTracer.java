package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/** Optional hook for block reads and writes performed through {@link FluidContext}. */
public interface FluidAccessTracer {
    void onBlockRead(WorldPos pos);

    void onBlockWrite(WorldPos pos);
}
