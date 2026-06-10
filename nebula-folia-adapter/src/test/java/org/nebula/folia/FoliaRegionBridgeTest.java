package org.nebula.folia;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FoliaRegionBridge} against the real Folia 26.1.2 API types.
 *
 * <p>{@code org.bukkit.Server} and {@code RegionScheduler} are interfaces, so we
 * stub only the few methods the bridge calls via {@link Proxy} — no running
 * server or mock framework needed. This verifies the bridge translates a Nebula
 * {@link WorldPos} into the correct Folia ownership query and region-scheduler
 * call (block→chunk coordinate conversion included).
 */
class FoliaRegionBridgeTest {

    private static final int DIM = 0;

    /** Records the (x,z) passed to isOwnedByCurrentRegion and getRegionScheduler/execute. */
    private record Calls(AtomicReference<int[]> ownershipQuery,
                         AtomicReference<int[]> scheduledChunk,
                         AtomicReference<Runnable> scheduledWork) {}

    private static Server stubServer(boolean ownsResult, Calls calls, World world, Plugin plugin) {
        RegionScheduler scheduler = (RegionScheduler) Proxy.newProxyInstance(
            RegionScheduler.class.getClassLoader(),
            new Class<?>[]{RegionScheduler.class},
            (proxy, method, args) -> {
                if (method.getName().equals("execute") && args != null && args.length == 5) {
                    // execute(Plugin, World, int chunkX, int chunkZ, Runnable)
                    calls.scheduledChunk().set(new int[]{(int) args[2], (int) args[3]});
                    calls.scheduledWork().set((Runnable) args[4]);
                    return null;
                }
                return defaultValue(method);
            });

        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isOwnedByCurrentRegion":
                    // The (World, int, int) overload the bridge uses.
                    if (args != null && args.length == 3 && args[1] instanceof Integer) {
                        calls.ownershipQuery().set(new int[]{(int) args[1], (int) args[2]});
                        return ownsResult;
                    }
                    return false;
                case "getRegionScheduler":
                    return scheduler;
                default:
                    return defaultValue(method);
            }
        };
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        if (r == void.class) return null;
        return null;
    }

    private static World stubWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (p, m, a) -> defaultValue(m));
    }

    private static Plugin stubPlugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (p, m, a) -> defaultValue(m));
    }

    @Test
    void ownsCurrentRegionPassesBlockCoordinatesThrough() {
        Calls calls = new Calls(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        World world = stubWorld();
        Plugin plugin = stubPlugin();
        FoliaRegionBridge bridge = new FoliaRegionBridge(stubServer(true, calls, world, plugin), plugin);

        WorldPos pos = new WorldPos(DIM, 137, 64, -42);
        assertTrue(bridge.ownsCurrentRegion(world, pos));
        // Ownership query receives the block X/Z (Folia resolves the region).
        assertEquals(137, calls.ownershipQuery().get()[0]);
        assertEquals(-42, calls.ownershipQuery().get()[1]);
    }

    @Test
    void ownsCurrentRegionReturnsServerVerdict() {
        Calls calls = new Calls(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        World world = stubWorld();
        Plugin plugin = stubPlugin();
        FoliaRegionBridge bridge = new FoliaRegionBridge(stubServer(false, calls, world, plugin), plugin);
        assertFalse(bridge.ownsCurrentRegion(world, new WorldPos(DIM, 0, 64, 0)));
    }

    @Test
    void runOnRegionSchedulesAtOwningChunk() {
        Calls calls = new Calls(new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        World world = stubWorld();
        Plugin plugin = stubPlugin();
        FoliaRegionBridge bridge = new FoliaRegionBridge(stubServer(true, calls, world, plugin), plugin);

        Runnable work = () -> {};
        // Block (137, -42) → chunk (137>>4, -42>>4) = (8, -3).
        bridge.runOnRegion(world, new WorldPos(DIM, 137, 64, -42), work);
        assertEquals(8, calls.scheduledChunk().get()[0]);
        assertEquals(-3, calls.scheduledChunk().get()[1]);
        assertEquals(work, calls.scheduledWork().get());
    }

    @Test
    void chunkConversionMatchesBlockShift() {
        assertEquals(8, FoliaRegionBridge.chunkX(new WorldPos(DIM, 137, 0, 0)));
        assertEquals(-3, FoliaRegionBridge.chunkZ(new WorldPos(DIM, 0, 0, -42)));
        assertEquals(0, FoliaRegionBridge.chunkX(new WorldPos(DIM, 15, 0, 0)));
        assertEquals(1, FoliaRegionBridge.chunkX(new WorldPos(DIM, 16, 0, 0)));
        assertEquals(-1, FoliaRegionBridge.chunkX(new WorldPos(DIM, -1, 0, 0)));
    }
}
