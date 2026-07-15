package org.nebula.core.scheduler;

import org.nebula.core.random.FidelityTier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Wires the active {@link FidelityTier} into the subsystems whose behaviour
 * is supposed to change with it (NEBULA-PATCH-2026-001 §变更四; arch doc §11,
 * §15.1, §16.2).
 *
 * <p>The previous slice published a tier via
 * {@link FidelityTier#currentTier()} but never re-applied it to anything:
 * the {@link SccContractor} threshold stayed at its default 128 regardless of
 * the live tier, the AI snapshot freshness rule had no reader, and a downgrade
 * to T2/T3 was effectively a no-op in production. This adapter closes that
 * gap: it owns the live {@link SccContractor} + any number of
 * {@link AiSnapshotStalenessSink} subscribers, and on every tier change
 * propagates the relaxation in one place.
 *
 * <p>Per-tier mapping:
 * <ul>
 *   <li><b>T0</b> — SCC threshold 128 (default); AI reads the live tick.</li>
 *   <li><b>T1</b> — SCC threshold 128 (per-entity random relaxation, no SCC
 *       effect); AI reads the live tick.</li>
 *   <li><b>T2</b> — SCC threshold 1024 (large-SCC mode);
 *       AI may use the previous tick's snapshot.</li>
 *   <li><b>T3</b> — SCC threshold {@code Integer.MAX_VALUE} (always contract);
 *       AI may use the previous tick's snapshot.</li>
 *   <li><b>FALLBACK</b> — SCC threshold 128 (single-thread escape, no
 *       degradation); AI falls back to the live tick.</li>
 * </ul>
 *
 * <p>The adapter is intentionally tier-agnostic on the subscriber side: any
 * subsystem that wants to react to a tier change registers a
 * {@link AiSnapshotStalenessSink} and the adapter calls it on the next tick
 * with the boolean. This keeps the per-subsystem wiring decoupled from the
 * tier's enum identity.
 */
public final class FidelityTierAdapter {

    private static final Logger LOG = Logger.getLogger(FidelityTierAdapter.class.getName());

    /** Default SCC threshold for strict tiers (T0/T1/FALLBACK). */
    public static final int STRICT_SCC_THRESHOLD = SccContractor.DEFAULT_THRESHOLD;
    /** SCC threshold under T2 (large-SCC mode). */
    public static final int T2_SCC_THRESHOLD = SccContractor.LARGE_THRESHOLD;
    /** SCC threshold under T3 (unlimited — always contract). */
    public static final int T3_SCC_THRESHOLD = SccContractor.UNLIMITED_THRESHOLD;

    /**
     * Subscriber that wants to know when "use the previous tick's AI snapshot"
     * becomes allowed. The active boolean is delivered on every tier change.
     */
    @FunctionalInterface
    public interface AiSnapshotStalenessSink {
        void onStalenessChange(boolean staleAllowed);
    }

    private final SccContractor contractor;
    private final List<AiSnapshotStalenessSink> aiSinks = new CopyOnWriteArrayList<>();
    private FidelityTier lastAppliedTier = null;

    public FidelityTierAdapter(SccContractor contractor) {
        this.contractor = Objects.requireNonNull(contractor, "contractor");
    }

    /** The contractor this adapter re-tunes on tier transitions. */
    public SccContractor contractor() {
        return contractor;
    }

    /**
     * Register a sink that wants to know when AI snapshot staleness is
     * permitted. The sink is invoked synchronously with the current value on
     * registration so callers don't need a separate "warm-up" call.
     */
    public void registerAiSink(AiSnapshotStalenessSink sink) {
        aiSinks.add(Objects.requireNonNull(sink, "sink"));
        try {
            sink.onStalenessChange(FidelityTier.currentTier().useStaleAiSnapshot());
        } catch (RuntimeException e) {
            // A misbehaving sink must not break registration of subsequent
            // sinks (or the rest of the per-tick dispatch).
            LOG.warning("AI staleness sink threw on registration: " + e.getMessage());
        }
    }

    /** Number of registered AI staleness sinks (for diagnostics). */
    public int aiSinkCount() {
        return aiSinks.size();
    }

    /**
     * Applies the active {@link FidelityTier} to the contractor and AI
     * sinks. Safe to call every tick — only a real tier change triggers work.
     *
     * @return true if the tier actually changed since the last call
     *         (callers can use this to gate expensive re-tuning)
     */
    public boolean applyActive() {
        FidelityTier tier = FidelityTier.currentTier();
        if (tier == lastAppliedTier) {
            return false;
        }
        apply(tier);
        lastAppliedTier = tier;
        return true;
    }

    /**
     * Forces application of {@code tier}, bypassing the change-detection
     * cache. Use this on initial bootstrap or after a {@code reset()} so the
     * downstream state catches up with the freshly-published tier.
     */
    public void apply(FidelityTier tier) {
        int newThreshold = thresholdFor(tier);
        int oldThreshold = contractor.threshold();
        contractor.setThreshold(newThreshold);
        boolean staleAllowed = tier.useStaleAiSnapshot();
        for (AiSnapshotStalenessSink sink : aiSinks) {
            try {
                sink.onStalenessChange(staleAllowed);
            } catch (RuntimeException e) {
                // A misbehaving sink must not break the others.
                LOG.warning("AI staleness sink threw: " + e.getMessage());
            }
        }
        if (oldThreshold != newThreshold) {
            LOG.fine(() -> "FidelityTierAdapter: tier=" + tier
                + " SCC threshold " + oldThreshold + " -> " + newThreshold
                + " staleAi=" + staleAllowed);
        }
    }

    /** The threshold the adapter would apply for the given tier. */
    public static int thresholdFor(FidelityTier tier) {
        return switch (Objects.requireNonNull(tier, "tier")) {
            case T0, T1, FALLBACK -> STRICT_SCC_THRESHOLD;
            case T2 -> T2_SCC_THRESHOLD;
            case T3 -> T3_SCC_THRESHOLD;
        };
    }

    /** The tier the most recent apply() call used (null before first apply). */
    public FidelityTier lastAppliedTier() {
        return lastAppliedTier;
    }
}
