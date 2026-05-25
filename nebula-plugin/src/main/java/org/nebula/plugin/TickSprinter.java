package org.nebula.plugin;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Accelerates DG1 verification and replay recording by running multiple
 * Nebula logical ticks per real server tick.
 *
 * <p>DG1 and replay are read-only hash operations — safe to batch.
 * Each real server tick fires {@link #onServerTick} which executes up to
 * {@link #burstPerTick} logical nebula ticks in a tight loop.
 *
 * <p>Use /nebula tick sprint <count> to start.
 */
public final class TickSprinter {

    private final Logger log;
    private final NebulaPlugin plugin;

    private volatile int burstPerTick = 50;     // logical ticks per real tick
    private volatile long sprintTarget = 0;
    private final AtomicLong sprintDone = new AtomicLong();
    private final AtomicBoolean active   = new AtomicBoolean(false);
    private long sprintStartMs = 0;

    public TickSprinter(NebulaPlugin plugin, Logger log) {
        this.plugin = plugin;
        this.log    = log;
    }

    /**
     * Start a sprint: run {@code ticks} logical ticks as fast as possible.
     *
     * @param ticks        total logical ticks to execute
     * @param burstPerTick how many logical ticks per real server tick (default 50)
     */
    public void start(long ticks, int burstPerTick) {
        this.sprintTarget  = ticks;
        this.burstPerTick  = burstPerTick;
        this.sprintDone.set(0);
        this.sprintStartMs = System.currentTimeMillis();
        this.active.set(true);
        log.info("TickSprint started: " + ticks + " logical ticks, burst=" + burstPerTick + "/real-tick");
    }

    public void stop() {
        active.set(false);
        log.info("TickSprint stopped at " + sprintDone.get() + " ticks");
    }

    /**
     * Called every real server tick. Executes up to burstPerTick logical ticks.
     *
     * @param realTickNumber the current server tick counter
     * @param world          the world to hash
     */
    public void onServerTick(long realTickNumber, World world) {
        if (!active.get()) return;

        long done = sprintDone.get();
        long remaining = sprintTarget - done;
        if (remaining <= 0) {
            active.set(false);
            reportCompletion();
            return;
        }

        int burst = (int) Math.min(burstPerTick, remaining);
        long logicalTick = realTickNumber * burstPerTick;

        Dg1Verifier dg1 = plugin.dg1Verifier();
        ReplaySession rs = plugin.replaySession();

        for (int i = 0; i < burst; i++) {
            logicalTick++;

            // Drive DG1 if active
            if (dg1 != null && dg1.isActive()) {
                dg1.onTick(logicalTick, world);
            }
        }

        long newDone = sprintDone.addAndGet(burst);
        if (newDone >= sprintTarget) {
            active.set(false);
            reportCompletion();
        }
    }

    private void reportCompletion() {
        long ms = System.currentTimeMillis() - sprintStartMs;
        log.info(String.format("TickSprint complete: %d logical ticks in %.1f s (%.0f ticks/s)",
            sprintDone.get(), ms / 1000.0, sprintDone.get() * 1000.0 / Math.max(ms, 1)));
    }

    public boolean isActive()    { return active.get(); }
    public long sprintDone()     { return sprintDone.get(); }
    public long sprintTarget()   { return sprintTarget; }
    public int  burstPerTick()   { return burstPerTick; }
}
