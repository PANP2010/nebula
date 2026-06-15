package org.nebula.core.scheduler;

import org.nebula.core.random.FidelityTier;

import java.util.Objects;

/**
 * Monitors tick health and triggers fidelity tier downgrades
 * (arch doc §16.2; NEBULA-PATCH-2026-001 §变更四).
 *
 * <p>Downgrade path: T0 → T1 → T2 → T3 → single-thread fallback.
 * Upgrades are never automatic — admin must issue /nebula fidelity reset.
 *
 * <p>Triggers:
 * <ul>
 *   <li>T0→T1: consecutive 10 ticks with Random over-budget rate &gt; 5%</li>
 *   <li>T1→T2: MSPT &gt; 50ms for 30 consecutive seconds (600 ticks)</li>
 *   <li>T2→T3: MSPT &gt; 50ms for 60 consecutive seconds (1200 ticks)</li>
 *   <li>any→fallback: unrecoverable DAG build error (via {@link #forceFallback()})</li>
 * </ul>
 */
public final class FidelityDowngradeController {

    private static final int RANDOM_DOWNGRADE_THRESHOLD_TICKS = 10;
    private static final double RANDOM_OVER_BUDGET_RATE = 0.05;
    private static final int MSPT_T1_T2_THRESHOLD_TICKS = 600;   // 30s at 20 TPS
    private static final int MSPT_T2_T3_THRESHOLD_TICKS = 1200;  // 60s at 20 TPS
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

        // T0 → T1: Random over-budget for 10 consecutive ticks.
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
            return currentTier;
        }

        // T1 → T2 (600 ticks over MSPT) and T2 → T3 (1200 ticks over MSPT) are
        // both driven by sustained MSPT pressure. The threshold differs per
        // tier; a clean tick resets the streak.
        if (currentTier == FidelityTier.T1 || currentTier == FidelityTier.T2) {
            if (mspt > MSPT_LIMIT_MS) {
                consecutiveMsptExceeded++;
                int threshold = currentTier == FidelityTier.T1
                    ? MSPT_T1_T2_THRESHOLD_TICKS
                    : MSPT_T2_T3_THRESHOLD_TICKS;
                if (consecutiveMsptExceeded >= threshold) {
                    currentTier = currentTier == FidelityTier.T1
                        ? FidelityTier.T2
                        : FidelityTier.T3;
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
