package org.nebula.agent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Static hook called by instrumented CollectingNeighborUpdater bytecode.
 *
 * The plugin registers a callback at startup via register().
 * The hook calls it when neighbor updates fire.
 * This avoids classloader visibility issues: the plugin pushes to the hook,
 * rather than the hook pulling from the plugin.
 */
public final class NeighborUpdateHooks {

    private static final Logger LOG = Logger.getLogger(NeighborUpdateHooks.class.getName());

    /** Callback interface that the Nebula plugin registers. */
    public interface NeighborUpdateCallback {
        void onNeighborUpdate(String worldName, int x, int y, int z);
    }

    private static volatile NeighborUpdateCallback callback = null;
    private static volatile boolean enabled = false;
    private static final AtomicLong callCount = new AtomicLong();

    /**
     * Sentinel flag set to {@code true} by the ASM transformer's injected bytecode
     * at the start of each instrumented method.  If this remains {@code false}
     * after retransform, the instrumentation silently failed and Nebula must
     * fall back to the shadow executor to avoid data corruption.
     */
    public static volatile boolean hooksActive = false;

    private NeighborUpdateHooks() {}

    /** Called by the plugin at startup to register the callback. */
    public static void register(NeighborUpdateCallback cb) {
        callback = cb;
        LOG.info("[Nebula/NeighborUpdateHooks] Callback registered: " + cb.getClass().getName());
    }

    /** Enables the hook. Called by the Nebula plugin after registering callback. */
    public static void setEnabled(boolean e) {
        enabled = e;
        LOG.info("[Nebula/NeighborUpdateHooks] Hook " + (e ? "enabled" : "disabled"));
    }

    public static boolean isEnabled() { return enabled; }
    public static long callCount() { return callCount.get(); }

    /**
     * Called at the top of every CollectingNeighborUpdater.addAndRun().
     * world and blockPos are Object to avoid classloader dependency.
     */
    public static void onNeighborUpdate(Object world, Object blockPos) {
        if (!enabled || callback == null) return;

        long count = callCount.incrementAndGet();
        if (count == 1 || count % 5000 == 0) {
            LOG.info("[Nebula/NeighborUpdateHooks] neighborChanged hook fired " + count + " times");
        }

        try {
            int x = (int) blockPos.getClass().getMethod("getX").invoke(blockPos);
            int y = (int) blockPos.getClass().getMethod("getY").invoke(blockPos);
            int z = (int) blockPos.getClass().getMethod("getZ").invoke(blockPos);
            String worldName = extractWorldName(world);
            callback.onNeighborUpdate(worldName, x, y, z);
        } catch (Exception e) {
            if (callCount.get() % 10000 == 1) {
                LOG.warning("[Nebula/NeighborUpdateHooks] Error: " + e);
            }
        }
    }

    private static String extractWorldName(Object world) {
        try {
            // Level.dimension() returns ResourceKey<Level>, .location() returns ResourceLocation
            // .toString() gives "minecraft:overworld" etc.
            Object key = world.getClass().getMethod("dimension").invoke(world);
            Object loc = key.getClass().getMethod("location").invoke(key);
            return loc.toString();
        } catch (Exception e) {
            return "world";
        }
    }
}
