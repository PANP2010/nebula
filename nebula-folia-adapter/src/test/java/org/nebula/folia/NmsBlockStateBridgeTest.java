package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneWorldState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NmsBlockStateBridge} against the real Folia API types, with
 * proxy-stubbed Bukkit objects whose power levels and block data are
 * controllable per test.
 */
class NmsBlockStateBridgeTest {

    private static final int DIM = 0;
    private RedstoneWorldState casStore;
    private NmsBlockStateBridge bridge;

    @BeforeEach
    void setUp() {
        casStore = new RedstoneWorldState();
        bridge = new NmsBlockStateBridge(casStore);
    }

    // ── Stub helpers ──────────────────────────────────────────────────────────

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        if (r == Material.class) return Material.REDSTONE_WIRE;
        return null;
    }

    /** Creates a Block stub that returns a RedstoneWire with the given power. */
    private static Block blockWithPower(int power) {
        RedstoneWire wireData = (RedstoneWire) Proxy.newProxyInstance(
            RedstoneWire.class.getClassLoader(),
            new Class<?>[]{RedstoneWire.class},
            (p, m, a) -> {
                if (m.getName().equals("getPower")) return power;
                if (m.getName().equals("getMaximumPower")) return 15;
                if (m.getName().equals("getAllowedFaces")) return Set.of();
                if (m.getName().equals("getMaterial")) return Material.REDSTONE_WIRE;
                return def(m);
            });
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return wireData;
                if (m.getName().equals("getType")) return Material.REDSTONE_WIRE;
                return def(m);
            });
    }

    /** Creates a Block stub that returns a non-powerable BlockData. */
    private static Block blockNonPowerable() {
        BlockData stoneData = (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class},
            (p, m, a) -> {
                if (m.getName().equals("getMaterial")) return Material.STONE;
                return def(m);
            });
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return stoneData;
                if (m.getName().equals("getType")) return Material.STONE;
                return def(m);
            });
    }

    /** Creates a World stub that returns a specific Block for given coordinates. */
    private static World worldFor(WorldPos pos, Block block) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockAt") && a != null && a.length == 3) {
                    int x = (int) a[0], y = (int) a[1], z = (int) a[2];
                    if (x == pos.x() && y == pos.y() && z == pos.z()) return block;
                }
                return def(m);
            });
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void syncFromNms_readsRedstonePowerIntoCasStore() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        Block block = blockWithPower(7);
        World world = worldFor(pos, block);

        long version = bridge.syncFromNms(world, pos);

        assertTrue(version > 0, "CAS version should be > 0 after sync");
        assertEquals(7, casStore.getPowerLevel(pos));
    }

    @Test
    void syncFromNms_nonPowerableStoresMinusOne() {
        WorldPos pos = new WorldPos(DIM, 5, 64, 5);
        Block block = blockNonPowerable();
        World world = worldFor(pos, block);

        bridge.syncFromNms(world, pos);

        assertEquals(-1, casStore.getPowerLevel(pos));
    }

    @Test
    void readNmsPower_returnsFoliaPowerWithoutTouchingCas() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        Block block = blockWithPower(11);
        World world = worldFor(pos, block);

        // Seed the CAS store with a DIFFERENT (shadow-computed) value. A read-only
        // NMS sample must return Folia's 11 while leaving the shadow's 3 intact —
        // this is what lets a SETTLED-DIAG snapshot compare nebula!=folia honestly
        // instead of clobbering nebula=X to folia=Y (the divergence tautology).
        casStore.putPowerLevel(pos, 3);

        int foliaPower = bridge.readNmsPower(world, pos);

        assertEquals(11, foliaPower, "should return Folia's authoritative power");
        assertEquals(3, casStore.getPowerLevel(pos),
            "read-only sample must NOT overwrite the shadow value in the CAS store");
    }

    @Test
    void readNmsPower_nonPowerableReturnsMinusOne() {
        WorldPos pos = new WorldPos(DIM, 5, 64, 5);
        Block block = blockNonPowerable();
        World world = worldFor(pos, block);

        // A previously-tracked position that is now air/stone: report -1, and do
        // NOT create or alter a CAS entry as a side effect of sampling.
        int foliaPower = bridge.readNmsPower(world, pos);

        assertEquals(-1, foliaPower, "non-powerable block reads as -1");
        assertEquals(-1, casStore.getPowerLevel(pos),
            "sampling a non-powerable block must not create a CAS entry (untracked reads as -1)");
    }

    @Test
    void syncToNms_writesPowerToPowerableBlock() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        int[] capturedPower = {0};
        boolean[] setBlockDataCalled = {false};

        RedstoneWire wireData = (RedstoneWire) Proxy.newProxyInstance(
            RedstoneWire.class.getClassLoader(),
            new Class<?>[]{RedstoneWire.class},
            (p, m, a) -> {
                if (m.getName().equals("getPower")) return capturedPower[0];
                if (m.getName().equals("setPower") && a != null && a.length == 1) {
                    capturedPower[0] = (int) a[0];
                    return null;
                }
                if (m.getName().equals("getMaximumPower")) return 15;
                if (m.getName().equals("getAllowedFaces")) return Set.of();
                return def(m);
            });
        Block block = (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return wireData;
                if (m.getName().equals("setBlockData")) {
                    setBlockDataCalled[0] = true;
                    return null;
                }
                if (m.getName().equals("getType")) return Material.REDSTONE_WIRE;
                return def(m);
            });
        World world = worldFor(pos, block);

        boolean result = bridge.syncToNms(world, pos, 12);

        assertTrue(result, "syncToNms should succeed for powerable block");
        assertTrue(setBlockDataCalled[0], "setBlockData should have been called");
        assertEquals(12, capturedPower[0], "power should be 12");
        assertEquals(12, casStore.getPowerLevel(pos), "CAS store should reflect 12");
    }

    @Test
    void syncToNms_nonPowerableReturnsFalse() {
        WorldPos pos = new WorldPos(DIM, 5, 64, 5);
        Block block = blockNonPowerable();
        World world = worldFor(pos, block);

        boolean result = bridge.syncToNms(world, pos, 5);

        assertFalse(result, "syncToNms should fail for non-powerable block");
        assertEquals(-1, casStore.getPowerLevel(pos));
    }

    @Test
    void syncToNms_clampsPowerToMaximum() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        int[] capturedPower = {0};

        RedstoneWire wireData = (RedstoneWire) Proxy.newProxyInstance(
            RedstoneWire.class.getClassLoader(),
            new Class<?>[]{RedstoneWire.class},
            (p, m, a) -> {
                if (m.getName().equals("getPower")) return capturedPower[0];
                if (m.getName().equals("setPower") && a != null && a.length == 1) {
                    capturedPower[0] = (int) a[0];
                    return null;
                }
                if (m.getName().equals("getMaximumPower")) return 15;
                if (m.getName().equals("getAllowedFaces")) return Set.of();
                return def(m);
            });
        Block block = (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockData")) return wireData;
                if (m.getName().equals("setBlockData")) return null;
                if (m.getName().equals("getType")) return Material.REDSTONE_WIRE;
                return def(m);
            });
        World world = worldFor(pos, block);

        bridge.syncToNms(world, pos, 99);  // way above max 15

        assertEquals(15, capturedPower[0], "power should be clamped to 15");
        assertEquals(15, casStore.getPowerLevel(pos), "CAS store should show 15");
    }

    @Test
    void bulkSyncFromNms_readsMultiplePositions() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        WorldPos pos2 = new WorldPos(DIM, 30, 64, 40);

        // Create a world that returns different blocks for different positions
        Block block10 = blockWithPower(5);
        Block block30 = blockWithPower(11);
        World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockAt") && a != null && a.length == 3) {
                    int x = (int) a[0];
                    if (x == 10) return block10;
                    if (x == 30) return block30;
                }
                return def(m);
            });

        bridge.bulkSyncFromNms(world, Set.of(pos1, pos2));

        assertEquals(5, casStore.getPowerLevel(pos1));
        assertEquals(11, casStore.getPowerLevel(pos2));
    }
}