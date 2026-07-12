package org.nebula.player;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Path B (Incremental Authority Transfer) gate per subsystem.
 *
 * <p>Monitors Folia-vs-Nebula divergence for a subsystem. After K consecutive
 * ticks where Nebula's output matches Folia's, the gate opens and the subsystem
 * transitions from shadow (observe-only) to authoritative (writes back to NMS).
 *
 * <p>Usage:
 * <pre>
 *   PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", 100);
 *   gate.recordTick(matched, total);
 *   if (gate.isOpen()) {
 *       // write back to Folia
 *   }
 * </pre>
 */
public final class PlayerAuthorityGate {

    private static final Logger LOG = Logger.getLogger(PlayerAuthorityGate.class.getName());

    public enum State {
        OBSERVING,
        CONDITIONAL,  // opened but watching for divergence
        AUTHORITATIVE  // fully authoritative
    }

    private final String subsystem;
    private final int consecutiveMatchThreshold;

    private final AtomicInteger consecutiveMatches = new AtomicInteger(0);
    private final AtomicLong totalTicks = new AtomicLong(0);
    private final AtomicLong matchedTicks = new AtomicLong(0);
    private volatile State state = State.OBSERVING;

    public PlayerAuthorityGate(String subsystem, int consecutiveMatchThreshold) {
        this.subsystem = subsystem;
        this.consecutiveMatchThreshold = consecutiveMatchThreshold;
    }

    /**
     * Called after each tick with the divergence check result.
     *
     * @param matched number of checks that matched (e.g. matched positions)
     * @param total   total number of checks (e.g. total entities)
     */
    public void recordTick(int matched, int total) {
        totalTicks.incrementAndGet();
        if (matched == total && total > 0) {
            matchedTicks.incrementAndGet();
            int streak = consecutiveMatches.incrementAndGet();
            if (streak >= consecutiveMatchThreshold && state == State.OBSERVING) {
                State prev = state;
                state = State.CONDITIONAL;
                LOG.info("[" + subsystem + "] Gate CONDITIONAL after " + streak
                    + " consecutive matched ticks (total=" + totalTicks + ", matched=" + matchedTicks + ")");
            } else if (streak >= consecutiveMatchThreshold * 2 && state == State.CONDITIONAL) {
                State prev = state;
                state = State.AUTHORITATIVE;
                LOG.info("[" + subsystem + "] Gate AUTHORITATIVE after " + streak
                    + " consecutive matched ticks — Nebula now writes to Folia");
            }
        } else {
            consecutiveMatches.set(0);
            if (state == State.AUTHORITATIVE) {
                LOG.warning("[" + subsystem + "] Divergence detected in AUTHORITATIVE state"
                    + " — gate remains open but flagged for review");
            }
        }
    }

    public boolean isOpen() {
        return state == State.CONDITIONAL || state == State.AUTHORITATIVE;
    }

    public boolean isAuthoritative() {
        return state == State.AUTHORITATIVE;
    }

    public State state() {
        return state;
    }

    public long totalTicks() {
        return totalTicks.get();
    }

    public long matchedTicks() {
        return matchedTicks.get();
    }

    public int consecutiveMatches() {
        return consecutiveMatches.get();
    }

    public void reset() {
        consecutiveMatches.set(0);
        state = State.OBSERVING;
        LOG.info("[" + subsystem + "] Gate reset to OBSERVING");
    }

    public String diagnostics() {
        return String.format("%s: state=%s ticks=%d matched=%d streak=%d/%d",
            subsystem, state, totalTicks.get(), matchedTicks.get(),
            consecutiveMatches.get(), consecutiveMatchThreshold);
    }
}
