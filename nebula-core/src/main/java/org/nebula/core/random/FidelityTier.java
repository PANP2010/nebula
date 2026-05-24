package org.nebula.core.random;

/**
 * Execution fidelity tier (arch doc §11.1–11.4).
 *
 * <ul>
 *   <li>{@link #T0}: Full determinism — random budgets enforced, shadow execution for all
 *       entities, bit-exact equivalence with vanilla single-thread order.</li>
 *   <li>{@link #T1}: Relaxed — entity random uses ThreadLocalRandom (seed = UUID hash +
 *       tick), World.random stays serial, loot table random stays deterministic.
 *       Statistical distribution matches vanilla but per-entity sequence may differ.</li>
 *   <li>{@link #T2}: Best-effort — used as emergency fallback (MSPT > 50ms for 30+ sec).
 *       DAG still runs but random budget is unlimited.</li>
 * </ul>
 */
public enum FidelityTier {
    T0,
    T1,
    T2,
    FALLBACK;

    /** Returns true if this tier requires budget enforcement. */
    public boolean requiresBudget() {
        return this == T0;
    }

    /** Returns true if random sequences must be bit-exact with vanilla. */
    public boolean strictRandom() {
        return this == T0;
    }

    /** Returns true if the DAG scheduler is active (not single-thread fallback). */
    public boolean dagEnabled() {
        return this != FALLBACK;
    }
}
