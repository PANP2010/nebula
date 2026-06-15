package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.FidelityTier;

import static org.junit.jupiter.api.Assertions.*;

class FidelityDowngradeControllerTest {

    @Test
    void startsAtConfiguredTier() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T0);
        assertEquals(FidelityTier.T0, ctrl.currentTier());
    }

    @Test
    void t0DowngradesToT1AfterConsecutiveRandomOverBudget() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T0);

        // 9 consecutive ticks over budget — no downgrade yet
        for (int i = 0; i < 9; i++) {
            ctrl.reportTick(0.06, 20);
        }
        assertEquals(FidelityTier.T0, ctrl.currentTier());

        // 10th tick triggers downgrade
        ctrl.reportTick(0.06, 20);
        assertEquals(FidelityTier.T1, ctrl.currentTier());
    }

    @Test
    void randomCounterResetsOnGoodTick() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T0);

        for (int i = 0; i < 8; i++) {
            ctrl.reportTick(0.06, 20);
        }
        assertEquals(8, ctrl.consecutiveRandomOverBudgetCount());

        // One good tick resets
        ctrl.reportTick(0.03, 20);
        assertEquals(0, ctrl.consecutiveRandomOverBudgetCount());
        assertEquals(FidelityTier.T0, ctrl.currentTier());
    }

    @Test
    void t1DowngradesToT2AfterMsptExceeded() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T1);

        for (int i = 0; i < 599; i++) {
            ctrl.reportTick(0.0, 55);
        }
        assertEquals(FidelityTier.T1, ctrl.currentTier());

        ctrl.reportTick(0.0, 55);
        assertEquals(FidelityTier.T2, ctrl.currentTier());
    }

    @Test
    void forceFallbackImmediatelyDrops() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T0);
        ctrl.forceFallback();
        assertEquals(FidelityTier.FALLBACK, ctrl.currentTier());

        // Even good ticks don't recover from forced fallback
        ctrl.reportTick(0.0, 10);
        assertEquals(FidelityTier.FALLBACK, ctrl.currentTier());
    }

    @Test
    void resetRestoresTier() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T0);
        ctrl.forceFallback();
        ctrl.reset(FidelityTier.T0);
        assertEquals(FidelityTier.T0, ctrl.currentTier());
    }

    @Test
    void t2DowngradesToT3AfterSustainedMspt() {
        // Per NEBULA-PATCH-2026-001 §变更四: T2 → T3 after MSPT > 50ms for 60s
        // (1200 ticks). Below the threshold it stays at T2.
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T2);
        for (int i = 0; i < 1199; i++) {
            ctrl.reportTick(0.5, 100);
        }
        assertEquals(FidelityTier.T2, ctrl.currentTier());

        ctrl.reportTick(0.5, 100);
        assertEquals(FidelityTier.T3, ctrl.currentTier());
    }

    @Test
    void t2MsptCounterResetsOnGoodTick() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T2);
        for (int i = 0; i < 1000; i++) {
            ctrl.reportTick(0.0, 100);
        }
        assertEquals(1000, ctrl.consecutiveMsptExceededCount());
        ctrl.reportTick(0.0, 10); // good tick resets the streak
        assertEquals(0, ctrl.consecutiveMsptExceededCount());
        assertEquals(FidelityTier.T2, ctrl.currentTier());
    }

    @Test
    void t3IsTerminalUnderMetricPressure() {
        // T3 is "maximum parallelism"; only an unrecoverable DAG error
        // (forceFallback) drops below it. Metric pressure alone keeps it at T3.
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T3);
        for (int i = 0; i < 2000; i++) {
            ctrl.reportTick(0.9, 200);
        }
        assertEquals(FidelityTier.T3, ctrl.currentTier());
    }

    @Test
    void t3DropsToFallbackOnForcedError() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T3);
        ctrl.forceFallback();
        assertEquals(FidelityTier.FALLBACK, ctrl.currentTier());
    }

    @Test
    void fallbackDisablesDag() {
        assertFalse(FidelityTier.FALLBACK.dagEnabled());
        assertTrue(FidelityTier.T0.dagEnabled());
        assertTrue(FidelityTier.T2.dagEnabled());
        assertTrue(FidelityTier.T3.dagEnabled());
    }

    @Test
    void onlyT0EnforcesBudgetAndStrictRandom() {
        assertTrue(FidelityTier.T0.requiresBudget());
        assertTrue(FidelityTier.T0.strictRandom());
        for (FidelityTier t : new FidelityTier[]{FidelityTier.T1, FidelityTier.T2,
                FidelityTier.T3, FidelityTier.FALLBACK}) {
            assertFalse(t.requiresBudget(), t + " must not enforce budget");
            assertFalse(t.strictRandom(), t + " must not require strict random");
        }
    }
}
