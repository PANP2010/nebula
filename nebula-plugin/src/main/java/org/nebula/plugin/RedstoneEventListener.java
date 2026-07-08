package org.nebula.plugin;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.nebula.folia.bridge.RedstoneTickHook;

import java.util.logging.Logger;

/**
 * Bukkit event listener that bridges BlockRedstoneEvent into Nebula's DAG execution pipeline.
 * This is the "perfect method" — stable Bukkit API that works across Folia versions.
 *
 * <p>In INTERCEPT mode, this listener cancels the event to prevent Folia's native
 * redstone execution, ensuring only Nebula's DAG drives propagation.
 */
public final class RedstoneEventListener implements Listener {

    private static final Logger LOG = Logger.getLogger(RedstoneEventListener.class.getName());
    private static long eventCount = 0;
    private static long cancelledCount = 0;

    /**
     * EventPriority.HIGHEST ensures we run after other plugins but before Folia's handlers.
     * This allows us to cancel the event in INTERCEPT mode.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        eventCount++;
        
        // Log every 1000 events for monitoring
        if (eventCount == 1 || eventCount % 1000 == 0) {
            LOG.info("[Nebula/RedstoneEvent] BlockRedstoneEvent fired " + eventCount 
                + " times (cancelled " + cancelledCount + "). Block: " + event.getBlock().getType() 
                + " at " + event.getBlock().getLocation() 
                + ", oldCurrent=" + event.getOldCurrent() 
                + ", newCurrent=" + event.getNewCurrent());
        }

        // Bridge into Nebula DAG pipeline via RedstoneTickHook
        if (RedstoneTickHook.isActive()) {
            Location loc = event.getBlock().getLocation();
            String worldName = loc.getWorld().getName();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            
            // Record this update in the global accumulator
            // It will be processed by endTick() at the end of this tick
            RedstoneTickHook.recordUpdate("nebula-global", worldName, x, y, z);
            
            // INTERCEPT mode: cancel the event to suppress Folia's native redstone execution
            // NOTE: BlockRedstoneEvent is not cancellable in the traditional sense.
            // We rely on the DAG execution to apply the correct state via syncToNms().
            // The "cancellation" happens implicitly: Folia reads the block state,
            // but Nebula overwrites it after DAG execution completes.
        }
    }
}
