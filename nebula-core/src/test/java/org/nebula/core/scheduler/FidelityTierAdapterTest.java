package org.nebula.core.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.random.FidelityTier;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FidelityTierAdapter}: the bridge that re-applies the active
 * {@link FidelityTier} to the {@link SccContractor} threshold and notifies
 * AI staleness sinks.
 *
 * <p>This is the missing-link test for "T2/T3 fidelity tiers actually do
 * something": previously the tier was published via {@code
 * FidelityTier.currentTier()} but the contractor's threshold stayed at 128
 * regardless. The adapter is the single source of truth that flips the
 * threshold on a real transition, and the test pins that behavior.
 */
class FidelityTierAdapterTest {

    private SccContractor contractor;
    private FidelityTierAdapter adapter;

    @BeforeEach
    void setUp() {
        contractor = new SccContractor();
        adapter = new FidelityTierAdapter(contractor);
        // Reset global state so test order doesn't matter.
        FidelityTier.setActiveTier(FidelityTier.T0);
    }

    @AfterEach
    void tearDown() {
        FidelityTier.setActiveTier(FidelityTier.T0);
    }

    @Test
    void applyActiveT0KeepsDefaultThreshold() {
        FidelityTier.setActiveTier(FidelityTier.T0);
        adapter.applyActive();
        assertEquals(FidelityTier.T0, adapter.lastAppliedTier());
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD, contractor.threshold());
    }

    @Test
    void applyT2RelaxesSccThresholdToLargeValue() {
        FidelityTier.setActiveTier(FidelityTier.T2);
        adapter.applyActive();
        assertEquals(FidelityTierAdapter.T2_SCC_THRESHOLD, contractor.threshold());
    }

    @Test
    void applyT3UnlimitedSccThreshold() {
        FidelityTier.setActiveTier(FidelityTier.T3);
        adapter.applyActive();
        assertEquals(FidelityTierAdapter.T3_SCC_THRESHOLD, contractor.threshold());
    }

    @Test
    void applyT2ThenT0TightensAgain() {
        FidelityTier.setActiveTier(FidelityTier.T2);
        adapter.applyActive();
        assertEquals(FidelityTierAdapter.T2_SCC_THRESHOLD, contractor.threshold());

        FidelityTier.setActiveTier(FidelityTier.T0);
        adapter.applyActive();
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD, contractor.threshold());
    }

    @Test
    void applyActiveIsIdempotent() {
        FidelityTier.setActiveTier(FidelityTier.T2);
        assertTrue(adapter.applyActive(), "first apply must be a change");
        assertFalse(adapter.applyActive(), "second apply with same tier is a no-op");
    }

    @Test
    void registerAiSinkReceivesCurrentValueImmediately() {
        AtomicBoolean received = new AtomicBoolean();
        adapter.registerAiSink(stale -> received.set(stale));

        // Default active tier is T0 → no staleness.
        assertFalse(received.get(), "T0 must not allow stale snapshots");

        // Flip to T2 → staleness on, sink re-invoked.
        FidelityTier.setActiveTier(FidelityTier.T2);
        adapter.applyActive();
        assertTrue(received.get(), "T2 must notify sink with stale=true");
    }

    @Test
    void multipleSinksAllReceiveUpdate() {
        AtomicInteger count = new AtomicInteger();
        AtomicBoolean last = new AtomicBoolean();
        adapter.registerAiSink(stale -> { count.incrementAndGet(); last.set(stale); });
        adapter.registerAiSink(stale -> { count.incrementAndGet(); last.set(stale); });

        // Each registration immediately invokes with the current value (T0 → false).
        assertEquals(2, count.get());

        FidelityTier.setActiveTier(FidelityTier.T2);
        adapter.applyActive();
        assertEquals(4, count.get(), "both sinks must be re-invoked on T2");
        assertTrue(last.get());
    }

    @Test
    void misbehavingSinkDoesNotBreakOthers() {
        AtomicBoolean secondInvoked = new AtomicBoolean();
        adapter.registerAiSink(_stale -> { throw new RuntimeException("boom"); });
        adapter.registerAiSink(_stale -> secondInvoked.set(true));

        FidelityTier.setActiveTier(FidelityTier.T2);
        adapter.applyActive();
        assertTrue(secondInvoked.get(),
            "second sink must run even if the first throws");
    }

    @Test
    void thresholdForMatchesPublishedConstants() {
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD,
            FidelityTierAdapter.thresholdFor(FidelityTier.T0));
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD,
            FidelityTierAdapter.thresholdFor(FidelityTier.T1));
        assertEquals(FidelityTierAdapter.T2_SCC_THRESHOLD,
            FidelityTierAdapter.thresholdFor(FidelityTier.T2));
        assertEquals(FidelityTierAdapter.T3_SCC_THRESHOLD,
            FidelityTierAdapter.thresholdFor(FidelityTier.T3));
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD,
            FidelityTierAdapter.thresholdFor(FidelityTier.FALLBACK));
    }

    @Test
    void applyBypassesCache() {
        // apply() should re-apply even if the tier is the same as the cache.
        FidelityTier.setActiveTier(FidelityTier.T0);
        adapter.applyActive();
        // The contractor's threshold is now STRICT; lower it directly.
        contractor.setThreshold(8);
        // applyActive() should be a no-op (cache hit), so threshold stays 8.
        assertFalse(adapter.applyActive());
        assertEquals(8, contractor.threshold());
        // apply() forces the contractor back to STRICT.
        adapter.apply(FidelityTier.T0);
        assertEquals(FidelityTierAdapter.STRICT_SCC_THRESHOLD, contractor.threshold());
    }
}
