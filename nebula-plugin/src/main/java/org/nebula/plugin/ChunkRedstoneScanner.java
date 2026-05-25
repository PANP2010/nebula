package org.nebula.plugin;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneComponentType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Scans chunks as they load and unload, maintaining a live map of
 * redstone component positions → {@link RedstoneComponentType}.
 *
 * <p>This map feeds the {@link org.nebula.folia.bridge.RedstoneTickHook}'s
 * TaskResolver so it can create {@link org.nebula.core.scheduler.TaskNode}s
 * for each dirty position.
 *
 * <p>Folia loads chunks on the owning region thread, so scanning in the
 * event handler is thread-safe for that chunk's data.
 */
public final class ChunkRedstoneScanner implements Listener {

    /** Bukkit Material → Nebula RedstoneComponentType mapping. */
    private static final EnumMap<Material, RedstoneComponentType> MATERIAL_MAP =
        new EnumMap<>(Material.class);

    static {
        // Wires
        MATERIAL_MAP.put(Material.REDSTONE_WIRE,         RedstoneComponentType.REDSTONE_WIRE);
        MATERIAL_MAP.put(Material.REDSTONE_BLOCK,        RedstoneComponentType.REDSTONE_BLOCK);

        // Timing
        MATERIAL_MAP.put(Material.REPEATER,              RedstoneComponentType.REPEATER);
        MATERIAL_MAP.put(Material.COMPARATOR,            RedstoneComponentType.COMPARATOR);

        // Torches
        MATERIAL_MAP.put(Material.REDSTONE_TORCH,        RedstoneComponentType.REDSTONE_TORCH);
        MATERIAL_MAP.put(Material.REDSTONE_WALL_TORCH,   RedstoneComponentType.REDSTONE_TORCH);

        // Pistons
        MATERIAL_MAP.put(Material.PISTON,                RedstoneComponentType.PISTON);
        MATERIAL_MAP.put(Material.STICKY_PISTON,         RedstoneComponentType.STICKY_PISTON);
        MATERIAL_MAP.put(Material.PISTON_HEAD,           RedstoneComponentType.PISTON_HEAD);
        MATERIAL_MAP.put(Material.MOVING_PISTON,         RedstoneComponentType.PISTON_HEAD);

        // Observer
        MATERIAL_MAP.put(Material.OBSERVER,              RedstoneComponentType.OBSERVER);

        // Lamps and light
        MATERIAL_MAP.put(Material.REDSTONE_LAMP,         RedstoneComponentType.REDSTONE_LAMP);
        MATERIAL_MAP.put(Material.DAYLIGHT_DETECTOR,     RedstoneComponentType.DAYLIGHT_DETECTOR);

        // Containers
        MATERIAL_MAP.put(Material.HOPPER,                RedstoneComponentType.HOPPER);
        MATERIAL_MAP.put(Material.DISPENSER,             RedstoneComponentType.DISPENSER);
        MATERIAL_MAP.put(Material.DROPPER,               RedstoneComponentType.DROPPER);

        // Rails
        MATERIAL_MAP.put(Material.POWERED_RAIL,          RedstoneComponentType.POWERED_RAIL);
        MATERIAL_MAP.put(Material.ACTIVATOR_RAIL,        RedstoneComponentType.ACTIVATOR_RAIL);

        // Activators
        MATERIAL_MAP.put(Material.LEVER,                 RedstoneComponentType.LEVER);
        MATERIAL_MAP.put(Material.STONE_BUTTON,          RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.OAK_BUTTON,            RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.SPRUCE_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.BIRCH_BUTTON,          RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.JUNGLE_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.ACACIA_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.DARK_OAK_BUTTON,       RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.CRIMSON_BUTTON,        RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.WARPED_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.CHERRY_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.MANGROVE_BUTTON,       RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.BAMBOO_BUTTON,         RedstoneComponentType.BUTTON);
        MATERIAL_MAP.put(Material.POLISHED_BLACKSTONE_BUTTON, RedstoneComponentType.BUTTON);

        // Pressure plates
        MATERIAL_MAP.put(Material.STONE_PRESSURE_PLATE,  RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.OAK_PRESSURE_PLATE,    RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.SPRUCE_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.BIRCH_PRESSURE_PLATE,  RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.JUNGLE_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.ACACIA_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.DARK_OAK_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.CRIMSON_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.WARPED_PRESSURE_PLATE,  RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.POLISHED_BLACKSTONE_PRESSURE_PLATE, RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.LIGHT_WEIGHTED_PRESSURE_PLATE,      RedstoneComponentType.PRESSURE_PLATE);
        MATERIAL_MAP.put(Material.HEAVY_WEIGHTED_PRESSURE_PLATE,      RedstoneComponentType.PRESSURE_PLATE);

        // Doors / gates / trapdoors
        MATERIAL_MAP.put(Material.IRON_DOOR,             RedstoneComponentType.IRON_DOOR);
        MATERIAL_MAP.put(Material.OAK_FENCE_GATE,        RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.SPRUCE_FENCE_GATE,     RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.BIRCH_FENCE_GATE,      RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.JUNGLE_FENCE_GATE,     RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.ACACIA_FENCE_GATE,     RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.DARK_OAK_FENCE_GATE,   RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.CRIMSON_FENCE_GATE,    RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.WARPED_FENCE_GATE,     RedstoneComponentType.FENCE_GATE);
        MATERIAL_MAP.put(Material.IRON_TRAPDOOR,         RedstoneComponentType.TRAPDOOR);

        // TNT
        MATERIAL_MAP.put(Material.TNT,                   RedstoneComponentType.TNT);

        // Tripwire
        MATERIAL_MAP.put(Material.TRIPWIRE_HOOK,         RedstoneComponentType.TRIPWIRE_HOOK);
        MATERIAL_MAP.put(Material.TRIPWIRE,              RedstoneComponentType.TRIPWIRE);

        // Note block
        MATERIAL_MAP.put(Material.NOTE_BLOCK,            RedstoneComponentType.NOTE_BLOCK);
    }

    private final Map<WorldPos, RedstoneComponentType> componentMap;
    private final Logger log;
    private final AtomicInteger totalScanned = new AtomicInteger();
    private final AtomicInteger totalComponents = new AtomicInteger();

    public ChunkRedstoneScanner(Map<WorldPos, RedstoneComponentType> componentMap, Logger log) {
        this.componentMap = componentMap;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        int dimId = dimensionId(worldName);
        int count = scanChunk(chunk, dimId, true);
        if (count > 0) {
            log.info("Chunk (" + chunk.getX() + "," + chunk.getZ()
                + ") in " + worldName + ": " + count + " redstone components");
        }
        totalScanned.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        int dimId = dimensionId(worldName);
        scanChunk(chunk, dimId, false);
    }

    /**
     * Scans all blocks in {@code chunk}, adding to or removing from the component map.
     *
     * @param add {@code true} to add components, {@code false} to remove them
     * @return number of redstone components found
     */
    private int scanChunk(Chunk chunk, int dimId, boolean add) {
        int found = 0;
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    RedstoneComponentType type = MATERIAL_MAP.get(block.getType());
                    if (type != null) {
                        int wx = chunk.getX() * 16 + x;
                        int wz = chunk.getZ() * 16 + z;
                        WorldPos pos = new WorldPos(dimId, wx, y, wz);
                        if (add) {
                            componentMap.put(pos, type);
                            totalComponents.incrementAndGet();
                        } else {
                            if (componentMap.remove(pos) != null) {
                                totalComponents.decrementAndGet();
                            }
                        }
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /** Returns the total number of scanned chunks so far. */
    public int chunksScanned() { return totalScanned.get(); }

    /** Returns the total number of known component positions. */
    public int componentCount() { return totalComponents.get(); }

    /** Resolves a dimension name to an integer ID. */
    public static int dimensionId(String worldName) {
        if (worldName == null) return 0;
        return switch (worldName) {
            case "world_nether" -> -1;
            case "world_the_end" ->  1;
            default             ->  0;
        };
    }

    /** Returns the Material→Type mapping (read-only, for diagnostics). */
    public static Map<Material, RedstoneComponentType> materialMap() {
        return java.util.Collections.unmodifiableMap(MATERIAL_MAP);
    }
}
