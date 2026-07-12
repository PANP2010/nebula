package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.CoverageTrendAnalyzer.CoverageSnapshot;
import org.nebula.maintenance.CoverageTrendAnalyzer.TrendResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CoverageTrendAnalyzer}. The analyzer is pure
 * (no I/O, no globals) so the tests exercise the week-over-week
 * computation directly against synthetic history lists.
 */
class CoverageTrendAnalyzerTest {

    private static final String MC = "1.21.4";

    private static CoverageSnapshot snap(String iso, int total, int annotated) {
        return new CoverageSnapshot(Instant.parse(iso), MC, total, annotated);
    }

    @Test
    void emptyHistoryReturnsZeroTrend() {
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of());
        assertEquals(1.0, r.currentRatio(), 1e-9);
        assertEquals(0.0, r.deltaPercent(), 1e-9);
        assertEquals(0, r.methodsAdded());
        assertEquals(0, r.methodsRemoved());
    }

    @Test
    void singleSnapshotReportsCurrentRatioWithZeroDelta() {
        CoverageSnapshot only = snap("2026-07-12T10:00:00Z", 100, 25);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(only));
        assertEquals(0.25, r.currentRatio(), 1e-9);
        assertEquals(0.0, r.deltaPercent(), 1e-9);
    }

    @Test
    void weekOverWeekDeltaIsPercentagePointsNotRatio() {
        // 30% → 40% should be +10.0pp, not +33.3%.
        CoverageSnapshot prior = snap("2026-07-05T10:00:00Z", 100, 30);
        CoverageSnapshot latest = snap("2026-07-12T10:00:00Z", 100, 40);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(prior, latest));
        assertEquals(0.40, r.currentRatio(), 1e-9);
        assertEquals(10.0, r.deltaPercent(), 1e-9);
        assertEquals(10, r.methodsAdded());
        assertEquals(0, r.methodsRemoved());
    }

    @Test
    void negativeDeltaReportsMethodsRemoved() {
        CoverageSnapshot prior = snap("2026-07-05T10:00:00Z", 100, 50);
        CoverageSnapshot latest = snap("2026-07-12T10:00:00Z", 100, 40);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(prior, latest));
        assertEquals(-10.0, r.deltaPercent(), 1e-9);
        assertEquals(0, r.methodsAdded());
        assertEquals(10, r.methodsRemoved());
    }

    @Test
    void unsortedHistoryIsSortedInternally() {
        CoverageSnapshot a = snap("2026-07-12T10:00:00Z", 100, 50);
        CoverageSnapshot b = snap("2026-07-05T10:00:00Z", 100, 30);
        CoverageSnapshot c = snap("2026-07-08T10:00:00Z", 100, 40);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(a, b, c));
        // 7 days before 2026-07-12 is 2026-07-05. The most-recent
        // snapshot at or before 07-05 is `b` (30%). Latest is `a`
        // (50%). Delta = +20.0, methodsAdded = 20.
        assertEquals(0.50, r.currentRatio(), 1e-9);
        assertEquals(20.0, r.deltaPercent(), 1e-9);
        assertEquals(20, r.methodsAdded());
    }

    @Test
    void picksClosestSnapshotOlderThanSevenDays() {
        // Three snapshots: 21d old, 14d old, 1d old. The "previous"
        // should be the 14d-old one (closest to 7d before the latest).
        CoverageSnapshot t0 = snap("2026-06-21T10:00:00Z", 100, 20);
        CoverageSnapshot t1 = snap("2026-06-28T10:00:00Z", 100, 25);
        CoverageSnapshot t2 = snap("2026-07-11T10:00:00Z", 100, 35);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(t0, t1, t2));
        // 35% - 25% (14d-old) = +10.0, not +15.0 (which would pick 21d-old)
        assertEquals(10.0, r.deltaPercent(), 1e-9);
    }

    @Test
    void fallsBackToSecondMostRecentWhenNothingIsSevenDaysOld() {
        // If no snapshot is older than 7d, fall back to second-most-recent.
        CoverageSnapshot t0 = snap("2026-07-11T12:00:00Z", 100, 30);
        CoverageSnapshot t1 = snap("2026-07-12T10:00:00Z", 100, 50);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(t0, t1));
        // 50% - 30% = +20.0
        assertEquals(20.0, r.deltaPercent(), 1e-9);
    }

    @Test
    void formatDeltaProducesPlusSignAndTwoDecimals() {
        CoverageSnapshot prior = snap("2026-07-05T10:00:00Z", 100, 30);
        CoverageSnapshot latest = snap("2026-07-12T10:00:00Z", 100, 33);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(prior, latest));
        // 33/100 - 30/100 = 3.0pp
        assertTrue(r.formatDelta().startsWith("+3.00%"),
            "delta should be formatted as '+X.XX%': " + r.formatDelta());
        assertTrue(r.formatDelta().contains("annotator velocity"),
            "delta should include 'annotator velocity' tag: " + r.formatDelta());
    }

    @Test
    void zeroTotalReportsFullCoverage() {
        CoverageSnapshot prior = snap("2026-07-05T10:00:00Z", 0, 0);
        CoverageSnapshot latest = snap("2026-07-12T10:00:00Z", 0, 0);
        TrendResult r = CoverageTrendAnalyzer.analyze(List.of(prior, latest));
        assertEquals(1.0, r.currentRatio(), 1e-9);
        assertEquals(0.0, r.deltaPercent(), 1e-9);
    }

    @Test
    void coverageSnapshotRejectsAnnotatedExceedingTotal() {
        try {
            new CoverageSnapshot(Instant.now(), MC, 10, 11);
            assert false : "should have rejected annotated > total";
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
