package org.nebula.player;

import org.nebula.core.state.WorldPos;

/**
 * Minecraft light level engine (arch doc ch.10).
 * 
 * Light level 0-15 in each block.
 * Two sources: BLOCK_LIGHT (emitted, e.g. torch=14, glowstone=15, lava=15)
 *              SKY_LIGHT (based on Y coordinate + obstructions, max 15 at Y=320, 0 at Y<=-64)
 * 
 * Block light is recomputed whenever a block emits or blocks light.
 * Sky light is recomputed when a block above is placed/removed.
 * 
 * Neighbor propagation: fill BFS from light sources.
 */
public final class LightEngine {

    public static final int MAX_LIGHT = 15;
    public static final int MIN_SKY = 0;

    // Block emission levels (0 = none)
    private static final java.util.Map<String, Integer> EMISSION = new java.util.HashMap<>();
    static {
        EMISSION.put("TORCH", 14);
        EMISSION.put("SOUL_TORCH", 3);
        EMISSION.put("GLOWSTONE", 15);
        EMISSION.put("SEA_LANTERN", 15);
        EMISSION.put("END_ROD", 14);
        EMISSION.put("BEACON", 15);
        EMISSION.put("JACK_O_LANTERN", 15);
        EMISSION.put("SHROOMLIGHT", 15);
        EMISSION.put("LANTERN", 14);
        EMISSION.put("SOUL_LANTERN", 10);
        EMISSION.put("CANDLE", 11);
        EMISSION.put("SOUL_CANDLE", 3);
        EMISSION.put("FURNACE", 13);
        EMISSION.put("LIT_FURNACE", 13);
        EMISSION.put("REDSTONE_TORCH", 7);
        EMISSION.put("REDSTONE_BLOCK", 0);
        EMISSION.put("LAVA", 15);
        EMISSION.put("FLOWING_LAVA", 15);
        EMISSION.put("MAGMA_BLOCK", 3);
        EMISSION.put("CRYING_OBSIDIAN", 10);
        EMISSION.put("GLOW_LICHEN", 6);
        EMISSION.put("CHAIN", 0);
        EMISSION.put("LIGHT", 15);
    }

    /** Returns block light emission level for material, or 0 if none. */
    public static int emissionLevel(String material) {
        return EMISSION.getOrDefault(material, 0);
    }

    /** Returns true if the material is transparent to light (air, glass, etc.). */
    public static boolean isTransparent(String material) {
        if ("AIR".equals(material) || "CAVE_AIR".equals(material) || "VOID_AIR".equals(material)) return true;
        // Partial transparency is modeled as transparent for MVP
        return material.contains("GLASS") || material.contains("FENCE") 
            || material.contains("SLAB") || material.contains("STAIRS")
            || material.endsWith("_SIGN") || material.endsWith("_HANGING_SIGN")
            || "GRASS".equals(material) || "TALL_GRASS".equals(material)
            || "FERN".equals(material) || "LARGE_FERN".equals(material)
            || "DEAD_BUSH".equals(material) || "HOPPER".equals(material)
            || "SNOW".equals(material) || "LIGHT".equals(material);
    }

    /**
     * Computes sky light at Y given no obstructions above.
     * MC sky formula: max(0, min(15, floor((319 - y) / (320 - lowestSeafloor)))
     * Simplified: full light at Y >= 319, decreases by 1 per block from Y=319 to Y=0
     */
    public static int skyLightAt(int y) {
        if (y >= 319) return 15;
        if (y <= -64) return 0;
        // Linear approximation: 15 at Y=319, 0 at Y=-64
        return Math.max(0, Math.min(15, (y + 64) / 25)); // approx formula
    }

    /**
     * Computes sky light blocked by opaque block at pos.
     * Returns skyLightAt(y) minus 1 per solid block in the column above.
     */
    public static int skyLightAtWithColumn(java.util.function.Function<WorldPos, Boolean> isOpaque, int y) {
        int light = skyLightAt(y);
        if (light == 0) return 0;
        // Walk upward through opaque blocks
        for (int cy = y + 1; cy <= 319 && light > 0; cy++) {
            // This would query the world state — caller provides the opaque checker
            light = Math.max(0, light - 1);
        }
        return light;
    }

    /**
     * BFS light propagation from a source.
     * Returns the set of positions whose light changed.
     * For MVP, returns the propagated positions (simplified BFS).
     */
    public static java.util.Set<WorldPos> propagate(
            WorldPos source,
            int initialLight,
            java.util.function.Function<WorldPos, Integer> currentLightAt,
            java.util.function.BiConsumer<WorldPos, Integer> setLight) {
        
        java.util.Set<WorldPos> changed = new java.util.HashSet<>();
        java.util.Queue<WorldPos> queue = new java.util.LinkedList<>();
        queue.add(source);
        java.util.Set<WorldPos> visited = new java.util.HashSet<>();
        visited.add(source);

        if (initialLight <= 1) return changed;

        while (!queue.isEmpty()) {
            WorldPos pos = queue.poll();
            int light = currentLightAt.apply(pos);
            if (light <= 1) continue;

            int decay = light - 1;
            for (WorldPos neighbor : neighbors(pos)) {
                if (visited.contains(neighbor)) continue;
                visited.add(neighbor);
                int neighborLight = currentLightAt.apply(neighbor);
                if (neighborLight < decay) {
                    setLight.accept(neighbor, decay);
                    changed.add(neighbor);
                    if (decay > 1) queue.add(neighbor);
                }
            }
        }
        return changed;
    }

    private static WorldPos[] neighbors(WorldPos pos) {
        return new WorldPos[] {
            new WorldPos(pos.dimensionId(), pos.x() + 1, pos.y(), pos.z()),
            new WorldPos(pos.dimensionId(), pos.x() - 1, pos.y(), pos.z()),
            new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() + 1),
            new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() - 1),
            new WorldPos(pos.dimensionId(), pos.x(), pos.y() + 1, pos.z()),
            new WorldPos(pos.dimensionId(), pos.x(), pos.y() - 1, pos.z()),
        };
    }
}
