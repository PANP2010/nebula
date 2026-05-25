package org.nebula.redstone;

import org.nebula.core.state.WorldPos;

/**
 * Optional hook that observes every block read/write performed through a
 * {@link RedstoneTaskContext}. Used by RW-guard tests to verify actual
 * accesses stay within the declared {@code RWSet}.
 */
public interface RedstoneAccessTracer {
    void onBlockRead(WorldPos pos);
    void onBlockWrite(WorldPos pos);
}
