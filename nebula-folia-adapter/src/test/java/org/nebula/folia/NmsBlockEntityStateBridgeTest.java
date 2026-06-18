package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NmsBlockEntityStateBridge} against the real Folia API types,
 * with proxy-stubbed Bukkit tile-entity objects.
 */
class NmsBlockEntityStateBridgeTest {

    private static final int DIM = 0;

    private BlockEntityState casStore;
    private NmsBlockEntityStateBridge bridge;

    @BeforeEach
    void setUp() {
        casStore = new BlockEntityState();
        bridge = new NmsBlockEntityStateBridge(casStore);
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == short.class) return (short)0;
        if (r == byte.class) return (byte)0;
        if (r == double.class) return 0.0;
        if (r == float.class) return 0.0f;
        if (r == Material.class) return Material.HOPPER;
        if (r.isPrimitive()) return 0;
        return null;
    }

    private static WorldPos pos(int x) { return new WorldPos(DIM, x, 64, 0); }

    /** Creates a Hopper stub with given cooldown. Inventory returns empty slots. */
    private static Hopper hopperStub(int cooldown) {
        Inventory inv = (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
            (p, m, a) -> {
                if (m.getName().equals("getSize")) return 5;
                if (m.getName().equals("getItem")) return null; // empty slots
                if (m.getName().equals("getContents")) return new ItemStack[5];
                return def(m);
            });
        return (Hopper) Proxy.newProxyInstance(
            Hopper.class.getClassLoader(), new Class<?>[]{Hopper.class},
            (p, m, a) -> {
                if (m.getName().equals("getTransferCooldown")) return cooldown;
                if (m.getName().equals("getInventory")) return inv;
                if (m.getName().equals("update")) return true;
                return def(m);
            });
    }

    /** Creates a Furnace stub with given timers. Inventory returns empty slots. */
    private static Furnace furnaceStub(short burnTime, short cookTime, int cookTimeTotal) {
        // FurnaceInventory extends Inventory, must implement both
        Inventory inv = (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(),
            new Class<?>[]{Inventory.class, FurnaceInventory.class},
            (p, m, a) -> {
                if (m.getName().equals("getSize")) return 3;
                if (m.getName().equals("getItem")) return null;
                if (m.getName().equals("getResult")) return null;
                if (m.getName().equals("getFuel")) return null;
                if (m.getName().equals("getSmelting")) return null;
                return def(m);
            });
        return (Furnace) Proxy.newProxyInstance(
            Furnace.class.getClassLoader(), new Class<?>[]{Furnace.class},
            (p, m, a) -> {
                if (m.getName().equals("getBurnTime")) return burnTime;
                if (m.getName().equals("getCookTime")) return cookTime;
                if (m.getName().equals("getCookTimeTotal")) return cookTimeTotal;
                if (m.getName().equals("getInventory")) return inv;
                if (m.getName().equals("update")) return true;
                return def(m);
            });
    }

    /** Creates a Block stub that returns the given BlockState. */
    private static Block blockWithState(BlockState state) {
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> {
                if (m.getName().equals("getState")) return state;
                if (m.getName().equals("getType")) return Material.HOPPER;
                return def(m);
            });
    }

    /** Creates a World stub that returns a specific Block for a given position. */
    private static World worldFor(WorldPos targetPos, Block block) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                if (m.getName().equals("getBlockAt") && a != null && a.length == 3) {
                    int x = (int) a[0], y = (int) a[1], z = (int) a[2];
                    if (x == targetPos.x() && y == targetPos.y() && z == targetPos.z()) return block;
                }
                return def(m);
            });
    }

    @Test
    void syncHopperFromNms_readsCooldownAndSlots() {
        WorldPos p = pos(10);
        Hopper hopper = hopperStub(8);
        Block block = blockWithState(hopper);
        World world = worldFor(p, block);

        bridge.syncFromNms(world, p);

        assertEquals(8, casStore.get(new BlockEntityField(p, "transferCooldown")));
        // Slots are empty (null items) → 0 in CAS
        assertEquals(0, casStore.get(new BlockEntityField(p, "slot_0")));
        assertEquals(0, casStore.get(new BlockEntityField(p, "slot_2")));
    }

    @Test
    void syncFurnaceFromNms_readsTimersAndSlots() {
        WorldPos p = pos(20);
        Furnace furnace = furnaceStub((short)100, (short)50, 200);
        Block block = blockWithState(furnace);
        World world = worldFor(p, block);

        bridge.syncFromNms(world, p);

        assertEquals(100, casStore.get(new BlockEntityField(p, "burnTime")));
        assertEquals(50, casStore.get(new BlockEntityField(p, "cookTime")));
        assertEquals(200, casStore.get(new BlockEntityField(p, "cookTimeTotal")));
        // Slots are empty → 0 in CAS
        assertEquals(0, casStore.get(new BlockEntityField(p, "slot_0")));
    }

    @Test
    void syncHopperToNms_writesCooldownFromCas() {
        WorldPos p = pos(10);
        casStore.put(new BlockEntityField(p, "transferCooldown"), 12);

        int[] writtenCooldown = {0};
        Hopper hopper = (Hopper) Proxy.newProxyInstance(
            Hopper.class.getClassLoader(), new Class<?>[]{Hopper.class},
            (p2, m, a) -> {
                if (m.getName().equals("setTransferCooldown") && a != null) {
                    writtenCooldown[0] = (int) a[0];
                    return null;
                }
                if (m.getName().equals("getInventory")) {
                    return Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                        new Class<?>[]{Inventory.class}, (p3, m2, a2) -> def(m2));
                }
                if (m.getName().equals("update")) return true;
                return def(m);
            });
        Block block = blockWithState(hopper);
        World world = worldFor(p, block);

        bridge.syncToNms(world, p);

        assertEquals(12, writtenCooldown[0]);
    }

    @Test
    void syncFurnaceToNms_writesTimersFromCas() {
        WorldPos p = pos(20);
        casStore.put(new BlockEntityField(p, "burnTime"), 80);
        casStore.put(new BlockEntityField(p, "cookTime"), 30);
        casStore.put(new BlockEntityField(p, "cookTimeTotal"), 200);

        short[] writtenBurn = {0}, writtenCook = {0};
        Furnace furnace = (Furnace) Proxy.newProxyInstance(
            Furnace.class.getClassLoader(), new Class<?>[]{Furnace.class},
            (p2, m, a) -> {
                if (m.getName().equals("setBurnTime") && a != null) {
                    writtenBurn[0] = ((Number) a[0]).shortValue();
                    return null;
                }
                if (m.getName().equals("setCookTime") && a != null) {
                    writtenCook[0] = ((Number) a[0]).shortValue();
                    return null;
                }
                if (m.getName().equals("getInventory")) {
                    return Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                        new Class<?>[]{Inventory.class, FurnaceInventory.class}, (p3, m2, a2) -> def(m2));
                }
                if (m.getName().equals("update")) return true;
                return def(m);
            });
        Block block = blockWithState(furnace);
        World world = worldFor(p, block);

        bridge.syncToNms(world, p);

        assertEquals(80, writtenBurn[0]);
        assertEquals(30, writtenCook[0]);
    }

    @Test
    void syncFromNms_ignoresNonTileEntity() {
        WorldPos p = pos(99);
        // BlockState that is not Hopper or Furnace
        BlockState genericState = (BlockState) Proxy.newProxyInstance(
            BlockState.class.getClassLoader(), new Class<?>[]{BlockState.class},
            (p2, m, a) -> def(m));
        Block block = blockWithState(genericState);
        World world = worldFor(p, block);

        bridge.syncFromNms(world, p);

        // Nothing should be written — all fields remain at default 0
        assertEquals(0, casStore.size());
    }

    @Test
    void roundTrip_hopperCooldownPreserved() {
        WorldPos p = pos(10);
        // Write to CAS
        casStore.put(new BlockEntityField(p, "transferCooldown"), 5);

        // Read back from CAS
        int read = casStore.get(new BlockEntityField(p, "transferCooldown"));
        assertEquals(5, read, "CAS round-trip should preserve value");
    }
}