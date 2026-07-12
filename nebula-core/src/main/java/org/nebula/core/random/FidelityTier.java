package org.nebula.core.random;

import java.util.concurrent.atomic.AtomicReference;

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
 *
 * <p>The active tier is published via {@link #setActiveTier(FidelityTier)} from
 * {@link org.nebula.core.scheduler.FidelityDowngradeController#reportTick}. Every
 * per-subsystem decision (large SCC batches, stale AI snapshots) reads from this
 * global, so behaviour flips the moment the controller downgrades — without any
 * extra wiring on the caller side.
 */
public enum FidelityTier {
    T0,
    T1,
    T2,
    T3,
    FALLBACK;

    /**
     * Currently-published tier. Starts at T0; updated by
     * {@link org.nebula.core.scheduler.FidelityDowngradeController} after each
     * tick. Read with {@link #currentTier()}.
     */
    private static final AtomicReference<FidelityTier> ACTIVE = new AtomicReference<>(T0);

    /** Publishes the current tier (called by the downgrade controller). */
    public static void setActiveTier(FidelityTier tier) {
        ACTIVE.set(tier);
    }

    /** Returns the currently-published tier (defaults to T0 if never set). */
    public static FidelityTier currentTier() {
        return ACTIVE.get();
    }

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

    /**
     * T2+ relaxes the SCC contraction threshold: when an SCC overflows the
     * default batch (128) we accept the larger compound instead of serialising
     * with warning, trading collision-response determinism for throughput.
     */
    public boolean allowsLargeScc() {
        return this == T2 || this == T3;
    }

    /**
     * T2+ allows AI perception to read the previous tick's snapshot instead of
     * the live one — a one-tick freshness lag for entity AI, accepted under
     * relaxed determinism.
     */
    public boolean useStaleAiSnapshot() {
        return this == T2 || this == T3;
    }
}