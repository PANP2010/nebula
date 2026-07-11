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
}