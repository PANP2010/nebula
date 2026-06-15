package org.nebula.core.random;

/**
 * Execution fidelity tier (arch doc §11.1–11.4, §15.1; NEBULA-PATCH-2026-001 §变更四).
 *
 * <ul>
 *   <li>{@link #T0} (strict determinism): all subsystems bit-exact with vanilla
 *       single-thread order — random budgets enforced, shadow execution, AI uses
 *       latest-tick data. For technical/redstone servers needing exact timing.</li>
 *   <li>{@link #T1} (statistical determinism): entity random uses an independent
 *       per-entity seed (thread-local); AI allows 1-tick freshness lag; redstone,
 *       physics, and block updates stay T0-deterministic. Per-entity random
 *       sequence may differ but the distribution matches vanilla.</li>
 *   <li>{@link #T2} (relaxed determinism): entity collision-response order may
 *       occasionally differ (when an SCC can't fully unroll in one tick); AI
 *       perception uses the previous tick's snapshot; redstone stays T1. For
 *       high-player-count survival / minigame servers.</li>
 *   <li>{@link #T3} (maximum parallelism): best-effort parallelism with no
 *       determinism guarantee — all subsystems run at max parallelism; redstone
 *       microsteps may split across ticks in extreme cases. For creative servers
 *       and stress testing that do not need determinism.</li>
 *   <li>{@link #FALLBACK}: single-threaded vanilla execution — entered only on an
 *       unrecoverable DAG build error.</li>
 * </ul>
 */
public enum FidelityTier {
    T0,
    T1,
    T2,
    T3,
    FALLBACK;

    /** Returns true if this tier requires random-budget enforcement. */
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
