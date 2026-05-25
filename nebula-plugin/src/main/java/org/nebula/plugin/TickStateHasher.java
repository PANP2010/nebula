package org.nebula.plugin;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.nebula.replay.StateHashComputer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Computes a deterministic SHA-256 hash of the loaded world state each tick.
 *
 * <p>Only loaded chunks within the simulation distance are included —
 * this is the "observable state" that participates in Folia's tick function.
 * The hash covers:
 * <ul>
 *   <li>Block states of all loaded chunks (block type ID + block data ordinal)</li>
 *   <li>Global state: game time, weather state</li>
 * </ul>
 *
 * <p>For Phase 0, we scope to the tracked redstone component positions only —
 * full world serialization is too slow for per-tick use during initial validation.
 * The scope expands in Phase 1 after performance baseline is established.
 */
public final class TickStateHasher {

    /** Scope for Phase 0: only hash redstone component positions. */
    public enum HashScope {
        /** Only hash known redstone component positions (fast, Phase 0). */
        REDSTONE_ONLY,
        /** Hash all loaded chunks (slow, Phase 1+). */
        FULL_WORLD,
        /** Hash only block states — exclude game_time and weather (for INTERCEPT comparison). */
        BLOCK_STATE_ONLY
    }

    private final Logger log;
    private final HashScope scope;
    private final Map<org.nebula.core.state.WorldPos, org.nebula.redstone.RedstoneComponentType> componentMap;

    public TickStateHasher(
        Map<org.nebula.core.state.WorldPos, org.nebula.redstone.RedstoneComponentType> componentMap,
        HashScope scope,
        Logger log
    ) {
        this.componentMap = componentMap;
        this.scope = scope;
        this.log = log;
    }

    /**
     * Computes the current world state hash for the given world.
     * Called once per tick after all block/entity updates have been applied.
     *
     * @param world the Bukkit world to hash
     * @return 32-byte SHA-256 hash
     */
    public byte[] computeHash(World world) {
        return switch (scope) {
            case REDSTONE_ONLY, BLOCK_STATE_ONLY -> hashRedstoneComponents(world);
            case FULL_WORLD                      -> hashLoadedChunks(world);
        };
    }

    // ── REDSTONE_ONLY scope ───────────────────────────────────────────────────

    private byte[] hashRedstoneComponents(World world) {
        // Serialise only the known redstone positions — deterministic TreeMap order
        Map<String, byte[]> blockState = new TreeMap<>();
        for (org.nebula.core.state.WorldPos pos : componentMap.keySet()) {
            if (ChunkRedstoneScanner.dimensionId(world.getName()) != pos.dimensionId()) continue;
            try {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                // Key: "dim,x,y,z" ; Value: type ordinal (4 bytes)
                String key = pos.dimensionId() + "," + pos.x() + "," + pos.y() + "," + pos.z();
                byte[] value = ByteBuffer.allocate(4)
                    .putInt(block.getType().ordinal())
                    .array();
                blockState.put(key, value);
            } catch (Exception ignored) {
                // Chunk may be unloaded — skip
            }
        }

        // Global state: game time + weather
        long gameTime = world.getFullTime();
        boolean isThundering = world.isThundering();
        boolean hasStorm = world.hasStorm();
        Map<String, byte[]> globals = new TreeMap<>();
        globals.put("game_time", ByteBuffer.allocate(8).putLong(gameTime).array());
        globals.put("weather", new byte[]{(byte)(hasStorm ? 1 : 0), (byte)(isThundering ? 1 : 0)});

        return StateHashComputer.compute(blockState, Map.of(), Map.of(), globals);
    }

    // ── FULL_WORLD scope ──────────────────────────────────────────────────────

    private byte[] hashLoadedChunks(World world) {
        Map<String, byte[]> blockState = new TreeMap<>();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (Chunk chunk : world.getLoadedChunks()) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        Block block = chunk.getBlock(x, y, z);
                        if (block.getType().isAir()) continue; // skip air for efficiency
                        int wx = chunk.getX() * 16 + x;
                        int wz = chunk.getZ() * 16 + z;
                        String key = wx + "," + y + "," + wz;
                        blockState.put(key, ByteBuffer.allocate(4)
                            .putInt(block.getType().ordinal())
                            .array());
                    }
                }
            }
        }

        Map<String, byte[]> globals = new TreeMap<>();
        globals.put("game_time", ByteBuffer.allocate(8).putLong(world.getFullTime()).array());
        globals.put("weather", new byte[]{
            (byte)(world.hasStorm() ? 1 : 0),
            (byte)(world.isThundering() ? 1 : 0)
        });

        return StateHashComputer.compute(blockState, Map.of(), Map.of(), globals);
    }
}
