package org.nebula.core.state;

/**
 * Shared dimension-ID normalization utility.
 *
 * <p>Maps various world-name formats (Bukkit folder names, Minecraft
 * namespaced keys, Folia region identifiers) to the canonical integer
 * dimension IDs used by {@link WorldPos}.
 *
 * <p>Canonical mapping:
 * <ul>
 *   <li>{@code 0}  — Overworld</li>
 *   <li>{@code -1} — Nether</li>
 *   <li>{@code 1}  — The End</li>
 * </ul>
 *
 * <p>This single class replaces the previously duplicated (and subtly
 * inconsistent) heuristics in {@code WorldRedstoneScanner} and
 * {@code RedstoneTickHook}.
 */
public final class DimensionIds {

    private DimensionIds() {}

    /**
     * Resolve a dimension name to its canonical integer ID.
     *
     * <p>Handles all common formats:
     * <ul>
     *   <li>Bukkit folder names: {@code "world"}, {@code "world_nether"}, {@code "world_the_end"}</li>
     *   <li>Minecraft namespaced keys: {@code "minecraft:overworld"}, {@code "minecraft:the_nether"}, {@code "minecraft:the_end"}</li>
     *   <li>Suffix-based: anything ending with {@code "nether"} or {@code "the_nether"}</li>
     *   <li>Suffix-based: anything ending with {@code "end"} or {@code "the_end"}</li>
     * </ul>
     *
     * @param worldName the world name (may be {@code null})
     * @return the canonical dimension ID (defaults to {@code 0} for unknown names)
     */
    public static int fromName(String worldName) {
        if (worldName == null) return 0;

        // Exact match on Minecraft namespaced keys
        if (worldName.equals("minecraft:the_nether")) return -1;
        if (worldName.equals("minecraft:the_end"))    return  1;
        if (worldName.equals("minecraft:overworld"))  return  0;

        // Suffix-based matching for Bukkit folder names and custom names
        if (worldName.endsWith("_nether") || worldName.endsWith("nether")) return -1;
        if (worldName.endsWith("_the_end") || worldName.endsWith("the_end")
            || worldName.endsWith("_end") || worldName.equals("end")) return 1;

        return 0; // default to overworld
    }
}
