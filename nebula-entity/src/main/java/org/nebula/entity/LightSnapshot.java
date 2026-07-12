package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Snapshot for light propagation tasks (arch doc §10, P2.1).
 * Captures the light level at a cell and its six neighbours.
 */
public final class LightSnapshot {

    private final WorldPos pos;
    private final int blockLight;
    private final int skyLight;

    public LightSnapshot(WorldPos pos, int blockLight, int skyLight) {
        this.pos = pos;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
    }

    public WorldPos pos() { return pos; }
    public int blockLight() { return blockLight; }
    public int skyLight() { return skyLight; }
    public int combinedLight() { return Math.max(blockLight, skyLight); }

    public WorldPos north() { return new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() + 1); }
    public WorldPos south() { return new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() - 1); }
    public WorldPos east()  { return new WorldPos(pos.dimensionId(), pos.x() + 1, pos.y(), pos.z()); }
    public WorldPos west()  { return new WorldPos(pos.dimensionId(), pos.x() - 1, pos.y(), pos.z()); }
    public WorldPos up()    { return new WorldPos(pos.dimensionId(), pos.x(), pos.y() + 1, pos.z()); }
    public WorldPos down() { return new WorldPos(pos.dimensionId(), pos.x(), pos.y() - 1, pos.z()); }
}
