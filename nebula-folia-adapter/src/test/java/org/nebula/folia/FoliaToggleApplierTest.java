package org.nebula.folia;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.replay.ResolvedToggle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FoliaToggleApplier} against the real Folia 26.1.2 API types.
 *
 * <p>All Folia collaborators ({@code Server}, {@code RegionScheduler},
 * {@code World}, {@code Block}, {@code BlockData}/{@code Powerable}) are
 * interfaces, so we stub only the handful of methods the applier calls via
 * {@link Proxy} — no running server or mock framework. This verifies the applier
 * (a) dispatches to the owning region thread, (b) flips a lever's powered state
 * and writes it back <em>with physics</em> so the neighbour-update path fires,
 * (c) is a no-op when the target is already in the requested state, and (d)
 * refuses non-powerable targets rather than corrupting them.
 */
class FoliaToggleApplierTest {

    private static final int DIM = 0;

    /** Records what the stubbed world/block/scheduler observed. */
    private static final class Recorder {
        final AtomicReference<Runnable> scheduledWork = new AtomicReference<>();
        final AtomicReference<int[]> scheduledChunk = new AtomicReference<>();
        final AtomicBoolean setPoweredCalledWith = new AtomicBoolean();
        final AtomicBoolean setPoweredWasInvoked = new AtomicBoolean();
        final AtomicBoolean physicsApplied = new AtomicBoolean();
        final AtomicBoolean setBlockDataInvoked = new AtomicBoolean();
        final AtomicInteger getBlockAtCalls = new AtomicInteger();
        boolean currentPowered;
        boolean powerable = true;
    }

    private static Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        return null;
    }

    /** A BlockData that is (optionally) Powerable, tracking setPowered/isPowered. */
    private static BlockData stubBlockData(Recorder rec) {
        Class<?>[] ifaces = rec.powerable
            ? new Class<?>[]{Powerable.class}
            : new Class<?>[]{BlockData.class};
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isPowered" -> { return rec.currentPowered; }
                case "setPowered" -> {
                    rec.setPoweredWasInvoked.set(true);
                    rec.setPoweredCalledWith.set((boolean) args[0]);
                    rec.currentPowered = (boolean) args[0];
                    return null;
                }
                default -> { return defaultValue(method); }
            }
        };
        ClassLoader cl = Powerable.class.getClassLoader();
        return (BlockData) Proxy.newProxyInstance(cl, ifaces, h);
    }

    private static Block stubBlock(Recorder rec, BlockData data) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlockData" -> { return data; }
                case "setBlockData" -> {
                    rec.setBlockDataInvoked.set(true);
                    // setBlockData(BlockData, boolean applyPhysics)
                    if (args.length == 2 && args[1] instanceof Boolean b) {
                        rec.physicsApplied.set(b);
                    }
                    return null;
                }
                default -> { return defaultValue(method); }
            }
        };
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
            new Class<?>[]{Block.class}, h);
    }

    private static World stubWorld(Recorder rec, Block block) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getName().equals("getBlockAt") && args != null && args.length == 3) {
                rec.getBlockAtCalls.incrementAndGet();
                return block;
            }
            return defaultValue(method);
        };
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, h);
    }

    /** A region bridge whose scheduler records + immediately runs the work inline. */
    private static FoliaRegionBridge inlineBridge(Recorder rec, World world) {
        RegionScheduler scheduler = (RegionScheduler) Proxy.newProxyInstance(
            RegionScheduler.class.getClassLoader(),
            new Class<?>[]{RegionScheduler.class},
            (proxy, method, args) -> {
                if (method.getName().equals("execute") && args != null && args.length == 5) {
                    rec.scheduledChunk.set(new int[]{(int) args[2], (int) args[3]});
                    Runnable work = (Runnable) args[4];
                    rec.scheduledWork.set(work);
                    work.run(); // run inline so the region-thread body executes in-test
                    return null;
                }
                return defaultValue(method);
            });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (p, m, a) -> defaultValue(m));
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
            new Class<?>[]{Server.class}, (proxy, method, args) -> {
                if (method.getName().equals("getRegionScheduler")) return scheduler;
                return defaultValue(method);
            });
        return new FoliaRegionBridge(server, plugin);
    }

    @Test
    void applyDispatchesToOwningChunkAndFiresPhysics() {
        Recorder rec = new Recorder();
        rec.currentPowered = false;
        BlockData data = stubBlockData(rec);
        Block block = stubBlock(rec, data);
        World world = stubWorld(rec, block);
        FoliaToggleApplier applier = new FoliaToggleApplier(inlineBridge(rec, world), world);

        // Block (137, -42) → chunk (8, -3).
        applier.apply(7L, new ResolvedToggle(new WorldPos(DIM, 137, 64, -42), true));

        assertEquals(8, rec.scheduledChunk.get()[0], "dispatched to owning chunk X");
        assertEquals(-3, rec.scheduledChunk.get()[1], "dispatched to owning chunk Z");
        assertTrue(rec.setPoweredWasInvoked.get(), "lever powered state was set");
        assertTrue(rec.setPoweredCalledWith.get(), "set to powered=true");
        assertTrue(rec.setBlockDataInvoked.get(), "block data written back");
        assertTrue(rec.physicsApplied.get(), "applyPhysics=true so neighbour updates fire");
    }

    @Test
    void applyPoweringOffWritesFalse() {
        Recorder rec = new Recorder();
        rec.currentPowered = true;
        BlockData data = stubBlockData(rec);
        Block block = stubBlock(rec, data);
        World world = stubWorld(rec, block);
        FoliaToggleApplier applier = new FoliaToggleApplier(inlineBridge(rec, world), world);

        applier.apply(1L, new ResolvedToggle(new WorldPos(DIM, 0, 64, 0), false));

        assertTrue(rec.setPoweredWasInvoked.get());
        assertFalse(rec.setPoweredCalledWith.get(), "set to powered=false");
        assertTrue(rec.physicsApplied.get());
    }

    @Test
    void applyIsNoOpWhenAlreadyInTargetState() {
        Recorder rec = new Recorder();
        rec.currentPowered = true; // already powered
        BlockData data = stubBlockData(rec);
        Block block = stubBlock(rec, data);
        World world = stubWorld(rec, block);
        FoliaToggleApplier applier = new FoliaToggleApplier(inlineBridge(rec, world), world);

        applier.apply(3L, new ResolvedToggle(new WorldPos(DIM, 0, 64, 0), true));

        assertFalse(rec.setPoweredWasInvoked.get(), "no setPowered on a no-op toggle");
        assertFalse(rec.setBlockDataInvoked.get(), "no world write on a no-op toggle");
    }

    @Test
    void applyOnNonPowerableTargetIsSafeNoOp() {
        Recorder rec = new Recorder();
        rec.powerable = false; // e.g. someone bound a wire, not a lever
        BlockData data = stubBlockData(rec);
        Block block = stubBlock(rec, data);
        World world = stubWorld(rec, block);
        FoliaToggleApplier applier = new FoliaToggleApplier(inlineBridge(rec, world), world);

        // Must not throw, must not attempt a write.
        applier.apply(0L, new ResolvedToggle(new WorldPos(DIM, 0, 64, 0), true));
        assertFalse(rec.setBlockDataInvoked.get(), "non-powerable target is not written");
    }

    @Test
    void constructorRejectsNulls() {
        Recorder rec = new Recorder();
        World world = stubWorld(rec, stubBlock(rec, stubBlockData(rec)));
        assertThrows(NullPointerException.class,
            () -> new FoliaToggleApplier(null, world));
        assertThrows(NullPointerException.class,
            () -> new FoliaToggleApplier(inlineBridge(rec, world), null));
    }

    @Test
    void applyRejectsNullToggle() {
        Recorder rec = new Recorder();
        World world = stubWorld(rec, stubBlock(rec, stubBlockData(rec)));
        FoliaToggleApplier applier = new FoliaToggleApplier(inlineBridge(rec, world), world);
        assertThrows(NullPointerException.class, () -> applier.apply(0L, null));
    }
}
