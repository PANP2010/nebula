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
    void t2DoesNotDowngradeFurther() {
        FidelityDowngradeController ctrl = new FidelityDowngradeController(FidelityTier.T2);
        // Many bad ticks at T2 — stays at T2
        for (int i = 0; i < 1000; i++) {
            ctrl.reportTick(0.5, 100);
        }
        assertEquals(FidelityTier.T2, ctrl.currentTier());
    }

    @Test
    void fallbackDisablesDag() {
        assertFalse(FidelityTier.FALLBACK.dagEnabled());
        assertTrue(FidelityTier.T0.dagEnabled());
        assertTrue(FidelityTier.T2.dagEnabled());
    }
}
