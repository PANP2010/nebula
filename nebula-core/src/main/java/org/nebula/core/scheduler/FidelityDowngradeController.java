package org.nebula.core.scheduler;

import org.nebula.core.random.FidelityTier;

import java.util.Objects;

/**
 * Monitors tick health and triggers fidelity tier downgrades (arch doc §16.2).
 *
 * <p>Downgrade path: T0 → T1 → T2 → single-thread fallback.
 * Upgrades are never automatic — admin must issue /nebula fidelity reset.
 *
 * <p>Triggers:
 * <ul>
 *   <li>T0→T1: consecutive 10 ticks with Random over-budget rate &gt; 5%</li>
 *   <li>T1→T2: MSPT &gt; 50ms for 30 consecutive seconds (600 ticks)</li>
 *   <li>T2→fallback: unrecoverable DAG build error</li>
 * </ul>
 */
public final class FidelityDowngradeController {

    private static final int RANDOM_DOWNGRADE_THRESHOLD_TICKS = 10;
    private static final double RANDOM_OVER_BUDGET_RATE = 0.05;
    private static final int MSPT_DOWNGRADE_THRESHOLD_TICKS = 600;
    private static final long MSPT_LIMIT_MS = 50;

    private FidelityTier currentTier;
    private int consecutiveRandomOverBudget;
    private int consecutiveMsptExceeded;
    private boolean forcedFallback;

    public FidelityDowngradeController(FidelityTier initialTier) {
        this.currentTier = Objects.requireNonNull(initialTier);
    }

    public FidelityDowngradeController() {
        this(FidelityTier.T0);
    }

    /**
     * Called after each tick with the tick's metrics.
     *
     * @param randomOverBudgetRate fraction of entities that exceeded Random budget (0.0-1.0)
     * @param mspt                 milliseconds per tick for this tick
     * @return the current fidelity tier after potential downgrade
     */
    public FidelityTier reportTick(double randomOverBudgetRate, long mspt) {
        if (forcedFallback) return FidelityTier.FALLBACK;

        // T0 → T1: Random over-budget
        if (currentTier == FidelityTier.T0) {
            if (randomOverBudgetRate > RANDOM_OVER_BUDGET_RATE) {
                consecutiveRandomOverBudget++;
                if (consecutiveRandomOverBudget >= RANDOM_DOWNGRADE_THRESHOLD_TICKS) {
                    currentTier = FidelityTier.T1;
                    consecutiveRandomOverBudget = 0;
                }
            } else {
                consecutiveRandomOverBudget = 0;
            }
        }

        // T1 → T2: MSPT exceeded
        if (currentTier == FidelityTier.T1) {
            if (mspt > MSPT_LIMIT_MS) {
                consecutiveMsptExceeded++;
                if (consecutiveMsptExceeded >= MSPT_DOWNGRADE_THRESHOLD_TICKS) {
                    currentTier = FidelityTier.T2;
                    consecutiveMsptExceeded = 0;
                }
            } else {
                consecutiveMsptExceeded = 0;
            }
        }

        return currentTier;
    }

    /**
     * Called on unrecoverable DAG build error — immediately drops to fallback.
     */
    public void forceFallback() {
        forcedFallback = true;
        currentTier = FidelityTier.FALLBACK;
    }

    /**
     * Admin reset — restores to the given tier. Clears all counters.
     */
    public void reset(FidelityTier tier) {
        currentTier = Objects.requireNonNull(tier);
        forcedFallback = false;
        consecutiveRandomOverBudget = 0;
        consecutiveMsptExceeded = 0;
    }

    public FidelityTier currentTier() {
        return currentTier;
    }

    public int consecutiveRandomOverBudgetCount() {
        return consecutiveRandomOverBudget;
    }

    public int consecutiveMsptExceededCount() {
        return consecutiveMsptExceeded;
    }
}
