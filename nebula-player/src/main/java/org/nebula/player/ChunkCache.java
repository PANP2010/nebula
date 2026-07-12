package org.nebula.player;

import org.nebula.player.ChunkGenerationSystem.ChunkCoord;
import org.nebula.player.ChunkGenerationSystem.ChunkData;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory chunk cache with LRU eviction.
 *
 * Nebula loads chunks on-demand from the player position and keeps recently-used
 * chunks in memory for fast terrain queries (collision checks, block interactions).
 *
 * Eviction: when cache exceeds maxSize, remove least-recently-used chunks.
 */
public final class ChunkCache {

    private final int maxSize;
    private final ConcurrentHashMap<ChunkCoord, ChunkData> data = new ConcurrentHashMap<>();
    private final LinkedHashMap<ChunkCoord, Long> accessOrder = new LinkedHashMap<>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<ChunkCoord, Long> eldest) {
            return data.size() > maxSize && data.remove(eldest.getKey()) != null;
        }
    };
    private final Object lock = new Object();

    public ChunkCache(int maxSize) {
        this.maxSize = maxSize;
    }

    /** Get or generate a chunk at the given coordinate. */
    public ChunkData getOrGenerate(ChunkCoord coord) {
        // Fast path: already cached
        ChunkData existing = data.get(coord);
        if (existing != null) {
            touch(coord);
            return existing;
        }

        // Slow path: generate and cache
        synchronized (lock) {
            // Double-check after acquiring lock
            existing = data.get(coord);
            if (existing != null) {
                touch(coord);
                return existing;
            }

            ChunkData generated = ChunkGenerationSystem.generateChunk(coord);
            data.put(coord, generated);
            touch(coord);
            evictIfNeeded();
            return generated;
        }
    }

    /** Get a chunk if cached, null otherwise. */
    public ChunkData get(ChunkCoord coord) {
        ChunkData result = data.get(coord);
        if (result != null) touch(coord);
        return result;
    }

    /** Preload chunks around a center coordinate. */
    public void preloadAround(ChunkCoord center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkCoord c = new ChunkCoord(
                    center.dimensionId(),
                    center.chunkX() + dx,
                    center.chunkZ() + dz
                );
                if (!data.containsKey(c)) {
                    getOrGenerate(c); // generate if not cached
                }
            }
        }
    }

    /** Invalidate a chunk (e.g., when a block is placed/broken). */
    public void invalidate(ChunkCoord coord) {
        data.remove(coord);
    }

    /** Returns the number of cached chunks. */
    public int size() { return data.size(); }

    /** Clears all cached chunks. */
    public void clear() {
        synchronized (lock) {
            data.clear();
            accessOrder.clear();
        }
    }

    /** Returns all cached chunk coordinates. */
    public Set<ChunkCoord> cachedChunks() {
        return Set.copyOf(data.keySet());
    }

    private void touch(ChunkCoord coord) {
        synchronized (lock) {
            accessOrder.remove(coord);
            accessOrder.put(coord, System.nanoTime());
        }
    }

    private void evictIfNeeded() {
        while (data.size() > maxSize) {
            Map.Entry<ChunkCoord, Long> eldest = null;
            synchronized (lock) {
                eldest = accessOrder.entrySet().iterator().hasNext()
                    ? accessOrder.entrySet().iterator().next()
                    : null;
            }
            if (eldest != null) {
                data.remove(eldest.getKey());
            } else {
                break;
            }
        }
    }
}
