package org.nebula.core.random;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-entity random number budget tracker (arch doc §11.2).
 *
 * <p>Before executing entity tasks in T0 mode, the scheduler calls
 * {@link #allocate(long, int)} to pre-generate a budget {@code B} for each entity.
 * After execution, it calls {@link #evaluate(long, int)} to determine whether to
 * commit, expand, or downgrade.
 *
 * <p>Downgrade policy: if more than {@link #downgradeThreshold} fraction of
 * entities exceed their budget in a single tick, the tier is downgraded from T0 to T1
 * for that tick.  After {@link #downgradeWarningCount} consecutive downgrades, a
 * warning is emitted.
 */
public final class RandomBudget {

    /** Safety multiplier: budget = max(estimate * multiplier, minBudget). */
    public static final double DEFAULT_SAFETY_MULTIPLIER = 1.5;

    /** Minimum budget per entity even with zero estimate. */
    public static final int DEFAULT_MIN_BUDGET = 10;

    /** Fraction of over-budget entities that triggers a T0→T1 downgrade. */
    public static final double DEFAULT_DOWNGRADE_THRESHOLD = 0.05;

    /** Consecutive downgrades before a warning is emitted. */
    public static final int DEFAULT_DOWNGRADE_WARNING_COUNT = 10;

    public enum BudgetResult { COMMIT, EXPAND, DOWNGRADE }

    private final double safetyMultiplier;
    private final int minBudget;
    private final double downgradeThreshold;
    private final int downgradeWarningCount;

    // Per-entity historical max consumption (for adaptive budget calculation)
    private final ConcurrentHashMap<Long, Integer> historicalMax = new ConcurrentHashMap<>();

    // Current-tick statistics
    private final AtomicInteger tickEntityCount = new AtomicInteger();
    private final AtomicInteger tickOverBudgetCount = new AtomicInteger();
    private int consecutiveDowngrades = 0;

    public RandomBudget() {
        this(DEFAULT_SAFETY_MULTIPLIER, DEFAULT_MIN_BUDGET,
             DEFAULT_DOWNGRADE_THRESHOLD, DEFAULT_DOWNGRADE_WARNING_COUNT);
    }

    public RandomBudget(double safetyMultiplier, int minBudget,
                        double downgradeThreshold, int downgradeWarningCount) {
        this.safetyMultiplier = safetyMultiplier;
        this.minBudget = minBudget;
        this.downgradeThreshold = downgradeThreshold;
        this.downgradeWarningCount = downgradeWarningCount;
    }

    /**
     * Allocates a random budget for the given entity.
     *
     * @param entityId        entity UUID lower 64 bits
     * @param estimatedCalls  declared max calls from {@code @NebulaRW}
     * @return the allocated budget B
     */
    public int allocate(long entityId, int estimatedCalls) {
        int historical = historicalMax.getOrDefault(entityId, estimatedCalls);
        int base = Math.max(estimatedCalls, historical);
        return Math.max((int) Math.ceil(base * safetyMultiplier), minBudget);
    }

    /**
     * Evaluates a task's random consumption against its budget.
     *
     * @param entityId    entity that just executed
     * @param budget      budget allocated by {@link #allocate}
     * @param actualCalls actual calls made during execution
     * @return COMMIT if within budget; EXPAND if slightly over; DOWNGRADE if many entities over-budget
     */
    public BudgetResult evaluate(long entityId, int budget, int actualCalls) {
        tickEntityCount.incrementAndGet();

        // Update historical max
        historicalMax.merge(entityId, actualCalls, Math::max);

        if (actualCalls <= budget) {
            return BudgetResult.COMMIT;
        }

        int overBudget = tickOverBudgetCount.incrementAndGet();
        int total = tickEntityCount.get();
        double rate = total > 0 ? (double) overBudget / total : 0;

        if (rate >= downgradeThreshold) {
            return BudgetResult.DOWNGRADE;
        }
        return BudgetResult.EXPAND;
    }

    /**
     * Resets per-tick counters. Must be called at the start of each tick.
     * Returns true if this tick should be downgraded to T1 based on accumulated pressure.
     */
    public boolean beginTick() {
        tickEntityCount.set(0);
        tickOverBudgetCount.set(0);
        return consecutiveDowngrades >= downgradeWarningCount;
    }

    /**
     * Records the result of the tick for downgrade-pressure tracking.
     * Returns the number of consecutive downgrades seen.
     */
    public int endTick(boolean wasDowngraded) {
        if (wasDowngraded) {
            consecutiveDowngrades++;
        } else {
            consecutiveDowngrades = 0;
        }
        return consecutiveDowngrades;
    }

    /** Returns the fraction of entities over-budget in the current tick. */
    public double currentOverBudgetRate() {
        int total = tickEntityCount.get();
        int over = tickOverBudgetCount.get();
        return total > 0 ? (double) over / total : 0.0;
    }

    /** Returns the historical max calls for an entity, or 0 if unknown. */
    public int historicalMax(long entityId) {
        return historicalMax.getOrDefault(entityId, 0);
    }
}
