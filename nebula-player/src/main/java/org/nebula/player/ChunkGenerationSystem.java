package org.nebula.player;

import org.nebula.core.state.WorldPos;

/**
 * Chunk generation and loading system.
 *
 * Minecraft chunks are 16x16x256 blocks. Chunks are identified by
 * (chunkX, chunkZ) coordinates = floor(blockX / 16).
 *
 * This system handles:
 * - Chunk loading requests (on-demand, when player approaches)
 * - Chunk generation (terrain noise, biome, structure placement)
 * - Chunk unloading (when no players are nearby)
 * - Chunk provider coordination with Folia's async chunk system
 *
 * For the MVP: generates flat terrain with basic features.
 */
public final class ChunkGenerationSystem {

    /** Chunk identifier: (dimensionId, chunkX, chunkZ). */
    public record ChunkCoord(int dimensionId, int chunkX, int chunkZ) {
        public static ChunkCoord of(int dimensionId, int blockX, int blockZ) {
            return new ChunkCoord(dimensionId, blockX >> 4, blockZ >> 4);
        }

        public int blockMinX() { return chunkX << 4; }
        public int blockMinZ() { return chunkZ << 4; }
        public int blockMaxX() { return (chunkX << 4) + 15; }
        public int blockMaxZ() { return (chunkZ << 4) + 15; }

        public boolean containsBlock(int bx, int bz) {
            return bx >= blockMinX() && bx <= blockMaxX()
                && bz >= blockMinZ() && bz <= blockMaxZ();
        }
    }

    /** Chunk data snapshot. Immutable once generated. */
    public record ChunkData(
        ChunkCoord coord,
        int[] blockTypes,       // 16*16*256 = 65536 entries, Y-major
        byte[] blockLight,     // 16*16*256 = 65536 entries (0-15)
        byte[] skyLight,       // 16*16*256 = 65536 entries (0-15)
        long generatedAtTick
    ) {
        public String blockMaterial(int bx, int by, int bz) {
            int idx = by | ((bz & 0xF) << 8) | ((bx & 0xF) << 12);
            return materialFromId(blockTypes[idx]);
        }

        private static String materialFromId(int id) {
            // Simplified: id = block type table index
            // Real impl would use Block state registry
            if (id <= 0) return "AIR";
            return "STONE";
        }
    }

    // ── Terrain noise (simplified Perlin-like) ───────────────────────────────

    private static final long SEED = 12345L;

    /** Simple deterministic hash for terrain generation. */
    private static double hash(int x, int z, long seed) {
        long h = ((long) x * 1327218875L) ^ ((long) z * 982451653L) ^ seed;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & 0x1FFFFFFFFFFFFFL) / (double) 0x1FFFFFFFFFFFFFL; // 0-1
    }

    /** 2D Perlin-like noise. */
    public static double noise2D(int x, int z, long seed, int octaves) {
        double total = 0;
        double amplitude = 1;
        double frequency = 1;
        double maxValue = 0;
        for (int o = 0; o < octaves; o++) {
            total += (hash((int)(x * frequency), (int)(z * frequency), seed + o) * 2 - 1) * amplitude;
            maxValue += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return total / maxValue;
    }

    /**
     * Generates terrain height at (x, z) in the given dimension.
     * Uses simplex-like noise. Returns Y coordinate of the surface.
     */
    public static int terrainHeight(int x, int z, int dimensionId) {
        // Sea level = 64, height range 0-128
        double n = noise2D(x, z, SEED + dimensionId, 6);
        int height = (int) ((n + 1) * 0.5 * 64 + 64); // 64-128
        return Math.max(0, Math.min(255, height));
    }

    /**
     * Returns the block type ID at the given block position.
     * Simplified: surface = GRASS, below = DIRT, deep = STONE, bedrock = BEDROCK.
     */
    public static int blockAt(int x, int y, int z, int surfaceHeight) {
        if (y <= 0) return 7;  // BEDROCK
        if (y == surfaceHeight) {
            if (surfaceHeight <= 62) return 12; // SAND (beaches)
            if (surfaceHeight >= 100) return 8;  // SNOW (mountains)
            return 2; // GRASS
        }
        if (y < surfaceHeight - 4) return 1; // STONE
        return 3; // DIRT
    }

    /**
     * Returns sky light level at Y (accounts for open sky above).
     * Simplified: full sky light if column above is all air, 0 if fully blocked.
     */
    public static int skyLightAt(int y, int surfaceHeight) {
        if (y <= surfaceHeight) return 0;
        return 15;
    }

    /**
     * Generates a full chunk. Thread-safe (pure function of coord + seed).
     */
    public static ChunkData generateChunk(ChunkCoord coord) {
        int[] blocks = new int[65536];
        byte[] blockLight = new byte[65536];
        byte[] skyLight = new byte[65536];

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                int wx = coord.blockMinX() + bx;
                int wz = coord.blockMinZ() + bz;
                int surface = terrainHeight(wx, wz, coord.dimensionId());

                for (int by = 0; by < 256; by++) {
                    int idx = by | (bz << 8) | (bx << 12);
                    blocks[idx] = blockAt(wx, by, wz, surface);
                    skyLight[idx] = (byte) skyLightAt(by, surface);
                    blockLight[idx] = 0; // no block light emitters in MVP
                }
            }
        }

        return new ChunkData(coord, blocks, blockLight, skyLight,
            System.currentTimeMillis());
    }

    public static final int CHUNK_SIZE = 16;
    public static final int CHUNK_HEIGHT = 256;
    public static final int SEA_LEVEL = 62;
}
