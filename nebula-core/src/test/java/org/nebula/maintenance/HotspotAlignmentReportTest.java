package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.HotspotAlignmentReport.Gap;
import org.nebula.maintenance.HotspotAlignmentReport.Report;
import org.nebula.maintenance.MethodHotspotList.HotspotMethod;
import org.nebula.maintenance.MethodHotspotList.HotspotReport;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HotspotAlignmentReport}. Verifies the priority
 * bands (HIGH/MEDIUM/LOW) and the sort order of the gap list.
 */
class HotspotAlignmentReportTest {

    @Test
    void priorityBandsMatchBriefThresholds() {
        assertEquals("HIGH", HotspotAlignmentReport.priorityFor(10.0));
        assertEquals("HIGH", HotspotAlignmentReport.priorityFor(5.01));
        assertEquals("MEDIUM", HotspotAlignmentReport.priorityFor(5.0));
        assertEquals("MEDIUM", HotspotAlignmentReport.priorityFor(1.0));
        assertEquals("LOW", HotspotAlignmentReport.priorityFor(0.99));
        assertEquals("LOW", HotspotAlignmentReport.priorityFor(0.0));
    }

    @Test
    void alignFiltersAnnotatedMethodsAndSortsByCpu() {
        HotspotMethod h1 = new HotspotMethod("Entity", "aiStep", "", 12.3, true, "@NebulaRW()");
        HotspotMethod h2 = new HotspotMethod("Entity", "tick", "", 8.7, true, "@NebulaRW()");
        HotspotMethod h3 = new HotspotMethod("BlockEntityHopper", "sync", "", 6.1, false, "");
        HotspotMethod h4 = new HotspotMethod("World", "raidTick", "", 4.2, false, "");
        HotspotMethod h5 = new HotspotMethod("Foo", "bar", "", 0.5, false, "");
        HotspotMethod nativeFrame = new HotspotMethod("", "mach_msg2_trap", "", 3.0, false, "");
        HotspotReport report = new HotspotReport(
            Instant.now(), "test", 6,
            List.of(h1, h2, h3, h4, h5, nativeFrame),
            2, 4);

        HotspotAlignmentReport aligner = new HotspotAlignmentReport();
        Report r = aligner.align(report, Set.of());

        // h1, h2 are annotated (skipped). The native frame has no class
        // and is filtered (no @NebulaRW target). Remaining gaps: h3
        // (HIGH 6.1), h4 (MEDIUM 4.2), h5 (LOW 0.5) → 3 gaps, 2 annotated.
        assertEquals(3, r.gapCount());
        assertEquals(2, r.annotatedCount());
        assertEquals(3, r.gaps().size());
        // Sorted by CPU desc.
        assertEquals("BlockEntityHopper", r.gaps().get(0).className());
        assertEquals("HIGH", r.gaps().get(0).priority());
        assertEquals(6.1, r.gaps().get(0).cpuPercent(), 1e-9);
        assertEquals("World", r.gaps().get(1).className());
        assertEquals("MEDIUM", r.gaps().get(1).priority());
        assertEquals("Foo", r.gaps().get(2).className());
        assertEquals("LOW", r.gaps().get(2).priority());
    }

    @Test
    void externalAnnotatedSetAlsoDeduplicates() {
        HotspotMethod h1 = new HotspotMethod("Entity", "tick", "", 8.0, false, "");
        HotspotMethod h2 = new HotspotMethod("Foo", "bar", "", 2.0, false, "");
        HotspotReport report = new HotspotReport(
            Instant.now(), "test", 2, List.of(h1, h2), 0, 2);

        HotspotAlignmentReport aligner = new HotspotAlignmentReport();
        Report r = aligner.align(report, Set.of("Entity/tick"));

        // External set declares Entity/tick as annotated → 1 annotated, 1 gap.
        assertEquals(1, r.annotatedCount());
        assertEquals(1, r.gapCount());
        assertEquals("Foo/bar", r.gaps().get(0).displayName());
    }

    @Test
    void summaryFormatsCoverageAndGap() {
        HotspotMethod h1 = new HotspotMethod("Entity", "tick", "", 8.0, true, "@NebulaRW()");
        HotspotMethod h2 = new HotspotMethod("Foo", "bar", "", 2.0, false, "");
        HotspotReport report = new HotspotReport(
            Instant.now(), "test", 2, List.of(h1, h2), 1, 1);

        HotspotAlignmentReport aligner = new HotspotAlignmentReport();
        Report r = aligner.align(report, Set.of());
        String s = r.summary();
        assertTrue(s.contains("1/2"), "summary should include 1/2: " + s);
        assertTrue(s.contains("50.0%"), "summary should include 50.0%: " + s);
        assertTrue(s.contains("Gap: 1"), "summary should include 'Gap: 1': " + s);
    }

    @Test
    void gapRejectsOutOfRangePercent() {
        try {
            new Gap("A", "m", -1.0, "LOW");
            assert false : "should have rejected negative cpuPercent";
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            new Gap("A", "m", 101.0, "HIGH");
            assert false : "should have rejected cpuPercent > 100";
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
