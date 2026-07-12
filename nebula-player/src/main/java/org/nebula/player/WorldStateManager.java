package org.nebula.player;

import org.nebula.core.state.WorldPos;
import org.nebula.player.ChunkGenerationSystem.ChunkCoord;
import org.nebula.player.ChunkGenerationSystem.ChunkData;
import org.nebula.player.ChunkCache;

import java.util.*;

/**
 * Manages world state for the survival core.
 *
 * Coordinates:
 * - Chunk generation (via ChunkGenerationSystem)
 * - Chunk caching (via ChunkCache)
 * - Block reads/writes (getBlock, setBlock, getLight, setLight)
 * - Player-chunk affinity (maps players to the chunks they affect)
 *
 * Thread-safe: reads can happen concurrently, writes are serialized per chunk.
 */
public final class WorldStateManager {

    private final ChunkCache chunkCache;
    private final Map<WorldPos, String> blockOverrides = new HashMap<>();
    private final Object blockLock = new Object();

    public WorldStateManager(int chunkCacheSize) {
        this.chunkCache = new ChunkCache(chunkCacheSize);
    }

    // ── Block access ─────────────────────────────────────────────────────────

    /**
     * Get the material name at a world position.
     * Checks overrides first (placed/broken blocks), then chunk cache.
     */
    public String getBlock(int dimensionId, int x, int y, int z) {
        WorldPos wp = new WorldPos(dimensionId, x, y, z);
        synchronized (blockLock) {
            String overridden = blockOverrides.get(wp);
            if (overridden != null) return overridden;
        }

        ChunkCoord cc = ChunkCoord.of(dimensionId, x, z);
        ChunkData chunk = chunkCache.get(cc);
        if (chunk == null) return "AIR";

        int idx = blockIndex(x, y, z);
        if (idx < 0 || idx >= 65536) return "AIR";
        return chunk.blockMaterial(x & 0xF, y, z & 0xF);
    }

    /**
     * Set a block at a world position. Marks the chunk for re-render.
     */
    public void setBlock(int dimensionId, int x, int y, int z, String material) {
        WorldPos wp = new WorldPos(dimensionId, x, y, z);
        synchronized (blockLock) {
            blockOverrides.put(wp, material);
        }
        // Invalidate chunk cache so next read recomputes
        chunkCache.invalidate(ChunkCoord.of(dimensionId, x, z));
    }

    /**
     * Break (remove) a block. Equivalent to setBlock(..., "AIR").
     */
    public boolean breakBlock(int dimensionId, int x, int y, int z) {
        String current = getBlock(dimensionId, x, y, z);
        if ("AIR".equals(current)) return false;
        setBlock(dimensionId, x, y, z, "AIR");
        return true;
    }

    // ── Light access ─────────────────────────────────────────────────────────

    public int getSkyLight(int dimensionId, int x, int y, int z) {
        ChunkCoord cc = ChunkCoord.of(dimensionId, x, z);
        ChunkData chunk = chunkCache.get(cc);
        if (chunk == null) {
            return org.nebula.player.LightEngine.skyLightAt(y);
        }
        int idx = blockIndex(x, y, z);
        if (idx < 0 || idx >= 65536) return 0;
        return chunk.skyLight()[idx] & 0xFF;
    }

    public int getBlockLight(int dimensionId, int x, int y, int z) {
        ChunkCoord cc = ChunkCoord.of(dimensionId, x, z);
        ChunkData chunk = chunkCache.get(cc);
        if (chunk == null) return 0;
        int idx = blockIndex(x, y, z);
        if (idx < 0 || idx >= 65536) return 0;
        return chunk.blockLight()[idx] & 0xFF;
    }

    public int getLight(int dimensionId, int x, int y, int z) {
        return Math.max(getSkyLight(dimensionId, x, y, z),
                       getBlockLight(dimensionId, x, y, z));
    }

    // ── Chunk access ─────────────────────────────────────────────────────────

    public ChunkData getChunk(int dimensionId, int chunkX, int chunkZ) {
        return chunkCache.getOrGenerate(new ChunkCoord(dimensionId, chunkX, chunkZ));
    }

    public void preloadAround(int dimensionId, int x, int z, int radius) {
        ChunkCoord center = ChunkCoord.of(dimensionId, x, z);
        chunkCache.preloadAround(center, radius);
    }

    // ── World queries ────────────────────────────────────────────────────────

    /** Returns true if the block at the given position is solid (blocks movement). */
    public boolean isSolid(int dimensionId, int x, int y, int z) {
        String mat = getBlock(dimensionId, x, y, z);
        return org.nebula.player.LightEngine.isTransparent(mat);
    }

    /** Returns true if the block at the given position is air or replaceable. */
    public boolean isAir(int dimensionId, int x, int y, int z) {
        return "AIR".equals(getBlock(dimensionId, x, y, z));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int blockIndex(int bx, int by, int bz) {
        if (by < 0 || by >= 256) return -1;
        int lx = bx & 0xF;
        int lz = bz & 0xF;
        return by | (lz << 8) | (lx << 12);
    }

    public ChunkCache cache() { return chunkCache; }
}
