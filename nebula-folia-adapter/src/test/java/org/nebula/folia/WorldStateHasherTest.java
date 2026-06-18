package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RedstoneWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests {@link WorldStateHasher} with proxy-stubbed Bukkit objects.
 */
class WorldStateHasherTest {

    private static final int DIM = 0;

    private WorldStateHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new WorldStateHasher(bytes -> WorldPos.parse(new String(bytes)));
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == Material.class) return Material.REDSTONE_WIRE;
        return null;
    }

    /** Creates a Block stub with RedstoneWire power and connections. */
    private static Block redstoneWireBlock(int power) {
        BlockData data = (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class, AnaloguePowerable.class, RedstoneWire.class},
            (p, m, a) -> {
                if (m.getName().equals("getPower")) return power;
                if (m.getName().equals("getMaximumPower")) return 15;
                if (m.getName().equals("getMaterial")) return Material.REDSTONE_WIRE;
                if (m.getName().equals("getAllowedFaces")) return java.util.Set.of();
                if (m.getName().equals("getFace")) return RedstoneWire.Connection.NONE;
                return def(m);
            });
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return data;
                if (m.getName().equals("getType")) return Material.REDSTONE_WIRE;
                return def(m);
            });
    }

    /** Creates a Block stub with non-powerable data. */
    private static Block stoneBlock() {
        BlockData data = (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
            (p, m, a) -> {
                if (m.getName().equals("getMaterial")) return Material.STONE;
                return def(m);
            });
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return data;
                if (m.getName().equals("getType")) return Material.STONE;
                return def(m);
            });
    }

    /** Creates a World stub that returns specific blocks for positions. */
    private static World worldForBlocks(java.util.Map<WorldPos, Block> blocks) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockAt") && a != null && a.length == 3) {
                    int x = (int) a[0], y = (int) a[1], z = (int) a[2];
                    WorldPos key = new WorldPos(DIM, x, y, z);
                    return blocks.getOrDefault(key, stoneBlock());
                }
                if (m.getName().equals("getName")) return "test_world";
                return def(m);
            });
    }

    @Test
    void hashIsDeterministicForSameState() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        hasher.trackPosition(pos1);

        Block block = redstoneWireBlock(7);
        World world = worldForBlocks(java.util.Map.of(pos1, block));

        byte[] hash1 = hasher.hashState(world, 100);
        byte[] hash2 = hasher.hashState(world, 100);

        assertArrayEquals(hash1, hash2, "Same state should produce same hash");
    }

    @Test
    void differentTickProducesDifferentHash() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        hasher.trackPosition(pos1);

        Block block = redstoneWireBlock(7);
        World world = worldForBlocks(java.util.Map.of(pos1, block));

        byte[] hash100 = hasher.hashState(world, 100);
        byte[] hash200 = hasher.hashState(world, 200);

        assertNotEquals(HexFormat.of().formatHex(hash100),
                        HexFormat.of().formatHex(hash200),
                        "Different tick should produce different hash");
    }

    @Test
    void differentPowerProducesDifferentHash() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        hasher.trackPosition(pos1);

        World world7 = worldForBlocks(java.util.Map.of(pos1, redstoneWireBlock(7)));
        World world3 = worldForBlocks(java.util.Map.of(pos1, redstoneWireBlock(3)));

        byte[] hash7 = hasher.hashState(world7, 100);
        byte[] hash3 = hasher.hashState(world3, 100);

        assertNotEquals(HexFormat.of().formatHex(hash7),
                        HexFormat.of().formatHex(hash3),
                        "Different power should produce different hash");
    }

    @Test
    void trackingAndUntrackingChangesHash() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        WorldPos pos2 = new WorldPos(DIM, 20, 64, 30);

        Block block = redstoneWireBlock(5);
        World world = worldForBlocks(java.util.Map.of(pos1, block, pos2, block));

        hasher.trackPosition(pos1);
        byte[] hash1pos = hasher.hashState(world, 100);

        hasher.trackPosition(pos2);
        byte[] hash2pos = hasher.hashState(world, 100);

        assertNotEquals(HexFormat.of().formatHex(hash1pos),
                        HexFormat.of().formatHex(hash2pos),
                        "More tracked positions should change hash");

        hasher.untrackPosition(pos2);
        byte[] hashAgain = hasher.hashState(world, 100);

        assertArrayEquals(hash1pos, hashAgain,
            "Untracking should restore original hash");
    }

    @Test
    void nonPowerableBlockNotIncluded() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);

        // Stone block (not AnaloguePowerable)
        World world = worldForBlocks(java.util.Map.of(pos1, stoneBlock()));

        hasher.trackPosition(pos1);
        byte[] hash = hasher.hashState(world, 100);

        // Should be a valid hash (32 bytes for SHA-256), even with no powerable blocks
        assertEquals(32, hash.length, "SHA-256 should produce 32 bytes");
    }

    @Test
    void emptyTrackedSetProducesValidHash() {
        World world = worldForBlocks(java.util.Map.of());

        byte[] hash = hasher.hashState(world, 100);

        assertEquals(32, hash.length, "SHA-256 should produce 32 bytes even for empty state");
    }
}