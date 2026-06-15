package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.AnnotationCoverageDashboard.SubsystemCoverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationMaintenanceTest {

    // ── ChangeLevel migration policy ──────────────────────────────────────────

    @Test
    void changeLevelMigrationActions() {
        assertEquals(ChangeLevel.MigrationAction.AUTO_MIGRATE, ChangeLevel.LEVEL_0.migrationAction());
        assertEquals(ChangeLevel.MigrationAction.AUTO_DRAFT, ChangeLevel.LEVEL_1.migrationAction());
        assertEquals(ChangeLevel.MigrationAction.MANUAL_REVIEW, ChangeLevel.LEVEL_2.migrationAction());
    }

    @Test
    void onlyLevel2IsNotAutomatable() {
        assertTrue(ChangeLevel.LEVEL_0.isAutomatable());
        assertTrue(ChangeLevel.LEVEL_1.isAutomatable());
        assertFalse(ChangeLevel.LEVEL_2.isAutomatable());
    }

    // ── SubsystemCoverage ─────────────────────────────────────────────────────

    @Test
    void coverageRatioComputed() {
        SubsystemCoverage c = new SubsystemCoverage("redstone", 45, 50, 2);
        assertEquals(0.9, c.coverageRatio(), 1e-9);
    }

    @Test
    void emptyHotspotsIsFullyCovered() {
        assertEquals(1.0, new SubsystemCoverage("empty", 0, 0, 0).coverageRatio(), 1e-9);
    }

    @Test
    void annotatedCannotExceedHotspots() {
        assertThrows(IllegalArgumentException.class,
            () -> new SubsystemCoverage("bad", 51, 50, 0));
    }

    @Test
    void negativeCountsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new SubsystemCoverage("bad", -1, 50, 0));
    }

    // ── Dashboard aggregation ─────────────────────────────────────────────────

    @Test
    void overallCoverageAggregatesAcrossSubsystems() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        d.report(new SubsystemCoverage("redstone", 90, 100, 1));
        d.report(new SubsystemCoverage("entity", 50, 100, 3));
        // (90 + 50) / (100 + 100) = 0.7
        assertEquals(0.7, d.overallCoverageRatio(), 1e-9);
        assertEquals(4, d.totalLevel2Debt());
    }

    @Test
    void decayTargetMetWhenAboveNinetyFivePercentOfBaseline() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        // Current coverage 96%.
        d.report(new SubsystemCoverage("s", 96, 100, 0));
        // Prior baseline was 100% — 0.96 >= 0.95 * 1.0 → meets the <5%/yr decay goal.
        assertTrue(d.meetsDecayTarget(1.0));
    }

    @Test
    void decayTargetFailedWhenCoverageDecaysTooFar() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        // Current coverage 90% against a prior 100% baseline → 10% decay > 5%.
        d.report(new SubsystemCoverage("s", 90, 100, 0));
        assertFalse(d.meetsDecayTarget(1.0));
    }

    @Test
    void emptyDashboardReportsFullCoverage() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        assertEquals(1.0, d.overallCoverageRatio(), 1e-9);
        assertEquals(0, d.totalLevel2Debt());
        assertTrue(d.summary().contains("no subsystems"));
    }

    @Test
    void subsystemsSortedByName() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        d.report(new SubsystemCoverage("zebra", 1, 1, 0));
        d.report(new SubsystemCoverage("alpha", 1, 1, 0));
        assertEquals("alpha", d.subsystems().get(0).subsystem());
        assertEquals("zebra", d.subsystems().get(1).subsystem());
    }
}
