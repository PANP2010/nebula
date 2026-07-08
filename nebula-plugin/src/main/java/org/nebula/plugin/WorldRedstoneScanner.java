package org.nebula.plugin;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.nebula.core.state.DimensionIds;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneComponentType;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Scans world chunks for redstone components and registers them with
 * the Nebula plugin's component map.
 *
 * <p>This bridges the implementation gap between the DAG execution engine
 * (which requires registered components to generate dirty tasks) and the
 * live Folia world (which contains redstone blocks that need discovery).
 *
 * <p>Usage:
 * <pre>{@code
 * WorldRedstoneScanner scanner = new WorldRedstoneScanner(plugin);
 * scanner.scanWorld(world);
 * }</pre>
 */
public final class WorldRedstoneScanner {

    private static final Logger LOG = Logger.getLogger(WorldRedstoneScanner.class.getName());

    // Use string keys to avoid Material enum static init issues
    private static final Map<String, RedstoneComponentType> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("REDSTONE_WIRE", RedstoneComponentType.REDSTONE_WIRE);
        TYPE_MAP.put("REDSTONE_TORCH", RedstoneComponentType.REDSTONE_TORCH);
        TYPE_MAP.put("REDSTONE_WALL_TORCH", RedstoneComponentType.REDSTONE_TORCH);
        TYPE_MAP.put("REPEATER", RedstoneComponentType.REPEATER);
        TYPE_MAP.put("COMPARATOR", RedstoneComponentType.COMPARATOR);
        TYPE_MAP.put("REDSTONE_BLOCK", RedstoneComponentType.REDSTONE_BLOCK);
        TYPE_MAP.put("LEVER", RedstoneComponentType.REDSTONE_TORCH);
        TYPE_MAP.put("STONE_BUTTON", RedstoneComponentType.REDSTONE_TORCH);
        TYPE_MAP.put("OAK_BUTTON", RedstoneComponentType.REDSTONE_TORCH);
        TYPE_MAP.put("OBSERVER", RedstoneComponentType.REDSTONE_TORCH);
    }

    private final NebulaPlugin plugin;

    public WorldRedstoneScanner(NebulaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Scans all loaded chunks in the given world for redstone components.
     * Registers each discovered component via
     * {@link NebulaPlugin#registerRedstoneComponent(WorldPos, RedstoneComponentType)}.
     *
     * @param world the world to scan
     * @return the number of components registered
     */
    public int scanWorld(World world) {
        Chunk[] chunks = world.getLoadedChunks();
        int total = 0;
        for (Chunk chunk : chunks) {
            total += scanChunk(world, chunk);
        }
        LOG.info("WorldRedstoneScanner: scanned " + chunks.length + " chunks in '"
            + world.getName() + "', registered " + total + " redstone components");
        return total;
    }

    /**
     * Scans a single chunk for redstone components.
     *
     * @param world the world (for dimension ID)
     * @param chunk the chunk to scan
     * @return the number of components registered in this chunk
     */
    public int scanChunk(World world, Chunk chunk) {
        int count = 0;
        int dimId = DimensionIds.fromName(world.getName());
        int cx = chunk.getX() << 4;
        int cz = chunk.getZ() << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Block block = chunk.getBlock(x, y, z);
                    String matName = block.getType().name();
                    RedstoneComponentType type = TYPE_MAP.get(matName);
                    if (type != null) {
                        WorldPos pos = new WorldPos(dimId, cx + x, y, cz + z);
                        plugin.registerRedstoneComponent(pos, type);
                        // Levers/buttons collapse to REDSTONE_TORCH in componentMap
                        // (see ToggleSourceClassifier); track their identity here so
                        // the live-load driver can recover the toggle-source set.
                        if (ToggleSourceClassifier.isToggleSource(matName)) {
                            plugin.registerToggleSource(pos);
                        }
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Scans a single block. Used for incremental registration when
     * blocks are placed during gameplay.
     *
     * @param block the block to check
     * @return true if a redstone component was registered
     */
    public boolean scanBlock(Block block) {
        String matName = block.getType().name();
        RedstoneComponentType type = TYPE_MAP.get(matName);
        if (type == null) return false;

        int dimId = DimensionIds.fromName(block.getWorld().getName());
        WorldPos pos = new WorldPos(dimId, block.getX(), block.getY(), block.getZ());
        plugin.registerRedstoneComponent(pos, type);
        if (ToggleSourceClassifier.isToggleSource(matName)) {
            plugin.registerToggleSource(pos);
        }
        return true;
    }

    /**
     * Unregisters a block when it is removed.
     *
     * @param block the block that was removed
     * @return true if a component was unregistered
     */
    public boolean unregisterBlock(Block block) {
        String matName = block.getType().name();
        if (!TYPE_MAP.containsKey(matName)) return false;

        int dimId = DimensionIds.fromName(block.getWorld().getName());
        WorldPos pos = new WorldPos(dimId, block.getX(), block.getY(), block.getZ());
        plugin.unregisterRedstoneComponent(pos);
        if (ToggleSourceClassifier.isToggleSource(matName)) {
            plugin.unregisterToggleSource(pos);
        }
        return true;
    }
}