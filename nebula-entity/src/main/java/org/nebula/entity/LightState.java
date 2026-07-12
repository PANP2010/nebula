package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory light state for DAG computation (arch doc §10, P2.1).
 *
 * <p>Tracks block light and sky light per block position. The state is updated
 * by DAG-computed propagation tasks and used for cross-tick continuity.
 * This is the CAS store analogue for light — NOT backed by NMS, purely for
 * DAG computation.
 */
public final class LightState {

    private final ConcurrentHashMap<WorldPos, LightCell> cells = new ConcurrentHashMap<>();
    private volatile long currentTick;

    public LightState() {}

    public void beginTick(long tick) {
        this.currentTick = tick;
    }

    public int blockLightAt(WorldPos pos) {
        return cells.getOrDefault(pos, LightCell.EMPTY).blockLight;
    }

    public int skyLightAt(WorldPos pos) {
        return cells.getOrDefault(pos, LightCell.EMPTY).skyLight;
    }

    public int combinedLightAt(WorldPos pos) {
        LightCell c = cells.getOrDefault(pos, LightCell.EMPTY);
        return Math.max(c.blockLight, c.skyLight);
    }

    public void setBlockLight(WorldPos pos, int level) {
        cells.compute(pos, (k, existing) ->
            existing == null ? new LightCell(level, 0) :
                new LightCell(Math.max(existing.blockLight, level), existing.skyLight));
    }

    public void setSkyLight(WorldPos pos, int level) {
        cells.compute(pos, (k, existing) ->
            existing == null ? new LightCell(0, level) :
                new LightCell(existing.blockLight, Math.max(existing.skyLight, level)));
    }

    public void setLight(WorldPos pos, int blockLight, int skyLight) {
        cells.compute(pos, (k, existing) ->
            existing == null ? new LightCell(blockLight, skyLight) :
                new LightCell(Math.max(existing.blockLight, blockLight),
                               Math.max(existing.skyLight, skyLight)));
    }

    public LightCell getCell(WorldPos pos) {
        return cells.getOrDefault(pos, LightCell.EMPTY);
    }

    public record LightCell(int blockLight, int skyLight) {
        public static final LightCell EMPTY = new LightCell(0, 0);
    }
}
