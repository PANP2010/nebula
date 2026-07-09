package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
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

        assertEquals(8, casStore.get(new BlockEntityField(p, "transfer_cooldown")));
        // Slots are empty (null items) → 0 in CAS
        assertEquals(0, casStore.get(new BlockEntityField(p, "inventory.slots[0]")));
        assertEquals(0, casStore.get(new BlockEntityField(p, "inventory.slots[2]")));
    }

    @Test
    void syncFurnaceFromNms_readsTimersAndSlots() {
        WorldPos p = pos(20);
        Furnace furnace = furnaceStub((short)100, (short)50, 200);
        Block block = blockWithState(furnace);
        World world = worldFor(p, block);

        bridge.syncFromNms(world, p);

        // Bukkit burnTime (remaining fuel) maps to the model's fuel_time; cookTime
        // (progress) maps to cook_progress; cookTimeTotal to cook_total.
        assertEquals(100, casStore.get(new BlockEntityField(p, "fuel_time")));
        assertEquals(50, casStore.get(new BlockEntityField(p, "cook_progress")));
        assertEquals(200, casStore.get(new BlockEntityField(p, "cook_total")));
        // Slots are empty → 0 in CAS
        assertEquals(0, casStore.get(new BlockEntityField(p, "inventory.slots[0]")));
    }

    @Test
    void syncHopperToNms_writesCooldownFromCas() {
        WorldPos p = pos(10);
        casStore.put(new BlockEntityField(p, "transfer_cooldown"), 12);

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
        casStore.put(new BlockEntityField(p, "fuel_time"), 80);
        casStore.put(new BlockEntityField(p, "cook_progress"), 30);
        casStore.put(new BlockEntityField(p, "cook_total"), 200);

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
        casStore.put(new BlockEntityField(p, "transfer_cooldown"), 5);

        // Read back from CAS
        int read = casStore.get(new BlockEntityField(p, "transfer_cooldown"));
        assertEquals(5, read, "CAS round-trip should preserve value");
    }

    /**
     * Regression guard for the field-path key mismatch (the B3-class bug): the bridge
     * MUST populate the exact {@link BlockEntityField} path the pure action math reads,
     * or a synced NMS value never reaches the DAG. {@code BlockEntityActions.hopper}
     * reads its cooldown at {@code "transfer_cooldown"} (see the resolver's own tests in
     * nebula-entity, which read that literal key); this proves {@code syncFromNms} writes
     * there — the old {@code "transferCooldown"} would have left the action reading zero
     * and silently doing nothing.
     */
    @Test
    void syncFromNms_populatesTheCanonicalCooldownPathTheHopperActionReads() {
        WorldPos p = pos(10);
        Hopper hopper = hopperStub(3);
        Block block = blockWithState(hopper);
        World world = worldFor(p, block);

        bridge.syncFromNms(world, p);

        // The literal key BlockEntityActions.hopper / BlockEntityContext read.
        assertEquals(3, casStore.get(new BlockEntityField(p, "transfer_cooldown")),
            "cooldown must land where the hopper action reads it");
        // A stale key from before the fix must NOT be where the value went.
        assertEquals(0, casStore.get(new BlockEntityField(p, "transferCooldown")),
            "nothing should be written under the old camelCase key");
    }

    /**
     * A plain Container stub (e.g. a chest) with {@code size} empty slots. We cannot
     * construct a real non-empty {@link ItemStack} in a unit test (its static init needs
     * a live Bukkit registry — the same limitation is why no existing test reads a
     * non-zero amount either), so slots return null. The discriminating signal this stub
     * proves is that a Container's slots are <em>iterated</em> at all — which
     * {@link NmsBlockEntityStateBridge#syncFromNms} does NOT do for a non-Hopper/Furnace.
     */
    private static Container containerStub(int size) {
        Inventory inv = (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
            (p, m, a) -> {
                if (m.getName().equals("getSize")) return size;
                if (m.getName().equals("getItem")) return null; // empty slots
                return def(m);
            });
        return (Container) Proxy.newProxyInstance(
            Container.class.getClassLoader(), new Class<?>[]{Container.class},
            (p, m, a) -> {
                if (m.getName().equals("getInventory")) return inv;
                return def(m);
            });
    }

    /**
     * The neighbour-sync read the hopper transfer slice needs: a plain chest neighbour
     * (a {@link Container} but NOT a Hopper/Furnace) must have its slots iterated into
     * CAS. {@link NmsBlockEntityStateBridge#syncFromNms} silently skips such a block (the
     * phantom-empty-neighbour bug), so {@code syncInventoryFromNms} exists to read it.
     * Proven by the entry count: syncFromNms writes 0 entries for the chest, while
     * syncInventoryFromNms writes one per slot.
     */
    @Test
    void syncInventoryFromNms_iteratesContainerSlots_whereSyncFromNmsSkips() {
        WorldPos p = pos(30);
        Container chest = containerStub(27);
        Block block = blockWithState(chest);
        World world = worldFor(p, block);

        // syncFromNms treats the chest as a non-tile-entity → nothing written.
        bridge.syncFromNms(world, p);
        assertEquals(0, casStore.size(), "syncFromNms must skip a plain container");

        // syncInventoryFromNms iterates its slots → one CAS entry per slot.
        bridge.syncInventoryFromNms(world, p);
        assertEquals(27, casStore.size(), "every container slot must be read into CAS");
        assertEquals(0, casStore.get(new BlockEntityField(p, "inventory.slots[0]")),
            "slot 0 must land at the canonical path the hopper action reads");
        assertEquals(0, casStore.get(new BlockEntityField(p, "inventory.slots[26]")),
            "last slot must be read too");
    }

    /**
     * {@code syncInventoryFromNms} must read ONLY slots, never the neighbour's own
     * cooldown/timer fields — clobbering a neighbour hopper's transfer_cooldown here
     * would corrupt its own ticking state.
     */
    @Test
    void syncInventoryFromNms_doesNotTouchCooldownOrTimers() {
        WorldPos p = pos(31);
        Container chest = containerStub(27);
        Block block = blockWithState(chest);
        World world = worldFor(p, block);

        bridge.syncInventoryFromNms(world, p);

        assertTrue(casStore.fields().stream()
                .noneMatch(f -> f.fieldPath().value().equals("transfer_cooldown")),
            "neighbour inventory sync must not write a cooldown");
        assertTrue(casStore.fields().stream()
                .noneMatch(f -> f.fieldPath().value().equals("fuel_time")),
            "neighbour inventory sync must not write furnace timers");
    }

    /** A non-container neighbour (air above a bottom hopper) is a clean no-op, not an NPE. */
    @Test
    void syncInventoryFromNms_ignoresNonContainer() {
        WorldPos p = pos(32);
        BlockState genericState = (BlockState) Proxy.newProxyInstance(
            BlockState.class.getClassLoader(), new Class<?>[]{BlockState.class},
            (p2, m, a) -> def(m));
        Block block = blockWithState(genericState);
        World world = worldFor(p, block);

        bridge.syncInventoryFromNms(world, p);

        assertEquals(0, casStore.size(), "non-container neighbour writes nothing");
    }

    /**
     * The read-only settled sampler ({@code readNmsInventoryCount}) sums a container's
     * slot amounts WITHOUT touching CAS — the {@code folia=} value a BE-SETTLED snapshot
     * compares against Nebula's shadow. Committing into CAS instead would overwrite the
     * shadow count and make {@code nebula == folia} by construction (the divergence
     * tautology). We cannot build a non-empty ItemStack in a unit test (registry
     * limitation — the same reason no other test reads a non-zero amount), so an empty
     * 27-slot chest sums to 0; the discriminating signals are that it returns a
     * non-negative count for a container and writes ZERO CAS entries.
     */
    @Test
    void readNmsInventoryCount_sumsContainerSlotsWithoutTouchingCas() {
        WorldPos p = pos(40);
        Container chest = containerStub(27);
        Block block = blockWithState(chest);
        World world = worldFor(p, block);

        int folia = bridge.readNmsInventoryCount(world, p);

        assertEquals(0, folia, "empty container sums to 0 (not -1)");
        assertEquals(0, casStore.size(), "the read-only sampler must never write CAS");
    }

    /**
     * A non-container block (air, solid, a non-tile block) sampled for a BE-SETTLED
     * snapshot returns {@code -1} — the sentinel a grader reads as "not a tracked
     * container", distinct from an empty container's honest 0.
     */
    @Test
    void readNmsInventoryCount_returnsMinusOneForNonContainer() {
        WorldPos p = pos(41);
        BlockState genericState = (BlockState) Proxy.newProxyInstance(
            BlockState.class.getClassLoader(), new Class<?>[]{BlockState.class},
            (p2, m, a) -> def(m));
        Block block = blockWithState(genericState);
        World world = worldFor(p, block);

        assertEquals(-1, bridge.readNmsInventoryCount(world, p),
            "a non-container block reads -1, never a fabricated 0");
        assertEquals(0, casStore.size(), "the read-only sampler must never write CAS");
    }

    /**
     * The read-only furnace-timer sampler ({@code readNmsFurnaceTimers}) reads Folia's
     * authoritative burn/cook/total timers WITHOUT touching CAS — the {@code folia=} value
     * a timer-divergence grade compares against Nebula's shadow. Committing into CAS
     * instead (via {@code syncFromNms}) would overwrite the shadow timers and make
     * {@code nebula == folia} by construction (the divergence tautology). It maps the
     * Bukkit API names onto the canonical model names exactly as {@code syncFurnaceFromNms}
     * does: burnTime→fuel_time, cookTime→cook_progress, cookTimeTotal→cook_total.
     */
    @Test
    void readNmsFurnaceTimers_readsTimersInModelUnitsWithoutTouchingCas() {
        WorldPos p = pos(50);
        Furnace furnace = furnaceStub((short)120, (short)66, 200);
        Block block = blockWithState(furnace);
        World world = worldFor(p, block);

        NmsBlockEntityStateBridge.FurnaceTimerSample sample = bridge.readNmsFurnaceTimers(world, p);

        assertEquals(120, sample.fuelTime(), "burnTime maps to fuel_time");
        assertEquals(66, sample.cookProgress(), "cookTime maps to cook_progress");
        assertEquals(200, sample.cookTotal(), "cookTimeTotal maps to cook_total");
        assertEquals(0, casStore.size(), "the read-only sampler must never write CAS");
    }

    /**
     * A non-furnace block (a hopper, a chest, air, a solid) sampled for a furnace-timer
     * snapshot returns {@code null} — the sentinel a grader reads as "not a furnace",
     * distinct from a furnace whose timers happen to be zero. A hopper is the sharp case:
     * it is a tile entity {@code syncFromNms} DOES handle, so this proves the sampler
     * discriminates on {@link Furnace}, not merely on "is a tile entity".
     */
    @Test
    void readNmsFurnaceTimers_returnsNullForNonFurnace() {
        WorldPos p = pos(51);
        Hopper hopper = hopperStub(4);
        Block block = blockWithState(hopper);
        World world = worldFor(p, block);

        assertEquals(null, bridge.readNmsFurnaceTimers(world, p),
            "a non-furnace tile (hopper) reads null, never fabricated timers");
        assertEquals(0, casStore.size(), "the read-only sampler must never write CAS");
    }
}