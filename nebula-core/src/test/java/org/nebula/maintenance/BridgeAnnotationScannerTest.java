package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.maintenance.AnnotationCoverageDashboard.SubsystemCoverage;
import org.nebula.maintenance.BridgeAnnotationScanner.ScanTarget;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeAnnotationScannerTest {

    /** Test fixture: every public method is annotated. */
    public static class FullyAnnotated {
        @NebulaRW(
            readBlocks = {"{pos}"},
            writeBlocks = {"{pos}"},
            microStep = MicroStepBehavior.NONE,
            scc = SccBehavior.AUTO,
            verifiedAt = "test-fixture"
        )
        public void a() {}

        @NebulaRW(
            readBlocks = {"{pos}"},
            writeBlocks = {"{pos}"},
            microStep = MicroStepBehavior.NONE,
            scc = SccBehavior.AUTO,
            verifiedAt = "test-fixture"
        )
        public void b(int x) {}
    }

    /** Test fixture: only one method is annotated. */
    public static class PartiallyAnnotated {
        @NebulaRW(
            readBlocks = {"{pos}"},
            writeBlocks = {"{pos}"},
            microStep = MicroStepBehavior.NONE,
            scc = SccBehavior.AUTO,
            verifiedAt = "test-fixture"
        )
        public void annotatedMethod() {}

        public void plainMethod() {}
    }

    /** Test fixture: no annotations at all. */
    public static class Unannotated {
        public void plain() {}
    }

    private static int countPublicInstance(Class<?> cls) {
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            int mods = m.getModifiers();
            if (!(java.lang.reflect.Modifier.isPublic(mods)
                  && !java.lang.reflect.Modifier.isStatic(mods))) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            n++;
        }
        return n;
    }

    @Test
    void fullyAnnotatedSubsystemReportsFullCoverage() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        BridgeAnnotationScanner.scan(
            ScanTarget.of("fixture-full", FullyAnnotated.class), d);

        List<SubsystemCoverage> subsystems = d.subsystems();
        assertFalse(subsystems.isEmpty());
        SubsystemCoverage c = subsystems.getFirst();
        assertEquals("fixture-full", c.subsystem());
        assertEquals(2, c.annotatedMethods());
        assertEquals(2, c.totalHotspotMethods());
        assertEquals(1.0, c.coverageRatio(), 1e-9);
    }

    @Test
    void partiallyAnnotatedSubsystemReportsFractionalCoverage() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        BridgeAnnotationScanner.scan(
            ScanTarget.of("fixture-partial", PartiallyAnnotated.class), d);

        SubsystemCoverage c = d.subsystems().getFirst();
        assertEquals("fixture-partial", c.subsystem());
        // One method carries @NebulaRW; the other is plain.
        assertEquals(1, c.annotatedMethods());
        assertEquals(countPublicInstance(PartiallyAnnotated.class), c.totalHotspotMethods());
        assertTrue(c.coverageRatio() < 1.0);
    }

    @Test
    void unannotatedSubsystemReportsZeroCoverage() {
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        BridgeAnnotationScanner.scan(
            ScanTarget.of("fixture-none", Unannotated.class), d);

        SubsystemCoverage c = d.subsystems().getFirst();
        assertEquals("fixture-none", c.subsystem());
        assertEquals(0, c.annotatedMethods());
        assertEquals(1, c.totalHotspotMethods());
        assertEquals(0.0, c.coverageRatio(), 1e-9);
    }

    @Test
    void peerClassesAccumulate() {
        // When a subsystem declares multiple bridge classes (peer inventory),
        // their annotated + total counts must add up. This is the multi-bridge
        // case the real scan faces (NmsBlockStateBridge + NmsBlockEntityStateBridge
        // both feed the same logical subsystem family).
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        ScanTarget target = new ScanTarget("fixture-peers",
            PartiallyAnnotated.class, List.of(Unannotated.class));
        BridgeAnnotationScanner.scan(target, d);

        SubsystemCoverage c = d.subsystems().getFirst();
        // 1 (annotated on PartiallyAnnotated) + 0 (Unannotated has none)
        assertEquals(1, c.annotatedMethods());
        // 2 (PartiallyAnnotated) + 1 (Unannotated) = 3
        assertEquals(countPublicInstance(PartiallyAnnotated.class)
            + countPublicInstance(Unannotated.class),
            c.totalHotspotMethods());
    }

    @Test
    void subsystemCoverageRoundTripsThroughDashboardReport() {
        // B8 C5 wiring: the dashboard's report() surface must accept the
        // reflected (annotated, total) row straight from getSubsystemCoverage()
        // — no hand-typed denominator may slip in. Round-trip the partially
        // annotated fixture through the new flow and assert the dashboard's
        // per-subsystem row matches the reflected counts exactly.
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        List<BridgeAnnotationScanner.SubsystemCoverage> rows =
            BridgeAnnotationScanner.getSubsystemCoverage(List.of(
                ScanTarget.of("fixture-rt", PartiallyAnnotated.class)));
        assertFalse(rows.isEmpty(), "scanner must report at least one subsystem");
        BridgeAnnotationScanner.SubsystemCoverage row = rows.getFirst();

        // The dashboard accepts the scanner's row by copying annotated/total
        // through report() (the wiring NebulaPlugin.buildCoverageDashboard uses).
        d.report(new SubsystemCoverage(
            row.subsystem(), row.annotatedMethods(), row.totalBridgeMethods(), 0));

        SubsystemCoverage reported = d.subsystems().getFirst();
        assertEquals("fixture-rt", reported.subsystem());
        assertEquals(row.annotatedMethods(), reported.annotatedMethods());
        assertEquals(row.totalBridgeMethods(), reported.totalHotspotMethods());
        assertEquals(row.coverageRatio(), reported.coverageRatio(), 1e-9);
    }

    @Test
    void getSubsystemCoverageOverMultipleSubsystemsProducesOneRowEach() {
        // B8 C5 wiring: when a real plugin-side scan enumerates multiple
        // subsystems (e.g. redstone-bridge, block-entity-bridge, fluid-bridge,
        // entity-bridge) the scanner must produce one row per distinct
        // subsystem name, with the annotated/total counts per subsystem,
        // not a single collapsed row.
        List<ScanTarget> targets = List.of(
            ScanTarget.of("a", FullyAnnotated.class),
            ScanTarget.of("b", PartiallyAnnotated.class),
            ScanTarget.of("c", Unannotated.class));
        List<BridgeAnnotationScanner.SubsystemCoverage> rows =
            BridgeAnnotationScanner.getSubsystemCoverage(targets);

        assertEquals(3, rows.size(),
            "scanner must produce one row per subsystem, got: " + rows);
        var byName = new java.util.HashMap<String, BridgeAnnotationScanner.SubsystemCoverage>();
        for (var r : rows) byName.put(r.subsystem(), r);
        assertEquals(countPublicInstance(FullyAnnotated.class),
            byName.get("a").annotatedMethods());
        assertEquals(countPublicInstance(FullyAnnotated.class),
            byName.get("a").totalBridgeMethods());
        assertEquals(1, byName.get("b").annotatedMethods());
        assertEquals(countPublicInstance(PartiallyAnnotated.class),
            byName.get("b").totalBridgeMethods());
        assertEquals(0, byName.get("c").annotatedMethods());
        assertEquals(countPublicInstance(Unannotated.class),
            byName.get("c").totalBridgeMethods());
    }
}