package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.maintenance.AnnotationCoverageDashboard;
import org.nebula.maintenance.AnnotationCoverageDashboard.SubsystemCoverage;
import org.nebula.maintenance.BridgeAnnotationScanner;
import org.nebula.maintenance.HotspotAlignmentReport;
import org.nebula.maintenance.MethodHotspotList;
import org.nebula.maintenance.MethodHotspotList.HotspotMethod;
import org.nebula.maintenance.MethodHotspotList.HotspotReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HotspotInventoryBridge} — the wiring that drives the
 * {@link AnnotationCoverageDashboard} from a real
 * {@link MethodHotspotList.HotspotReport}.
 *
 * <p>Pinpoints the B8 C5 "real method-level hotspot inventory feeding
 * coverage dashboard" deliverable: a synthetic flat profile is parsed,
 * aligned against bridge scan annotations, and the dashboard reflects the
 * combined signal — both subsystems and totals.
 */
class HotspotInventoryBridgeTest {

    /** Bridge root with two annotated methods + one plain. */
    public static class BridgeRoot {
        @NebulaRW(
            readBlocks = {"{pos}"},
            writeBlocks = {"{pos}"},
            microStep = MicroStepBehavior.NONE,
            scc = SccBehavior.AUTO,
            verifiedAt = "test-fixture"
        )
        public void annotatedOne() {}

        @NebulaRW(
            readBlocks = {"{pos}"},
            writeBlocks = {"{pos}"},
            microStep = MicroStepBehavior.NONE,
            scc = SccBehavior.AUTO,
            verifiedAt = "test-fixture"
        )
        public void annotatedTwo() {}

        public void plain() {}
    }

    @Test
    void reportFeedsDashboardFromHotspotReport(@TempDir Path tmp) throws IOException {
        // Build a synthetic flat profile with 4 hotspot rows:
        //   - 2 already-annotated methods (covered)
        //   - 1 hotspot method with no annotation yet (gap)
        //   - 1 native frame (filtered)
        Path profile = tmp.resolve("flat.txt");
        Files.writeString(profile,
            "          ns  percent  samples  top\n"
            + "  ----------  -------  -------  ---\n"
            + "    36000000   40.00%       40  " + BridgeRoot.class.getSimpleName() + ".annotatedOne\n"
            + "    18000000   20.00%       20  " + BridgeRoot.class.getSimpleName() + ".annotatedTwo\n"
            + "    14000000   15.56%       14  SomeEntity.tick\n"
            + "     9000000   10.00%        9  mach_msg2_trap\n");

        MethodHotspotList list = new MethodHotspotList();
        HotspotReport hotspots = list.fromFlatProfile(profile, 5);

        // Inject annotation flags. (The parser leaves everything unannotated;
        // the dashboard needs the bridge scan to know what's covered.)
        java.util.List<HotspotMethod> annotated = new java.util.ArrayList<>();
        for (HotspotMethod h : hotspots.methods()) {
            boolean isAnnotated = h.methodName().startsWith("annotated");
            annotated.add(new HotspotMethod(
                h.className(), h.methodName(), h.descriptor(),
                h.cumulativePercent(), isAnnotated,
                isAnnotated ? "@NebulaRW(verifiedAt=test-fixture)" : ""));
        }
        HotspotReport rebuilt = new HotspotReport(
            Instant.now(), profile.toString(), 5,
            List.copyOf(annotated),
            2, 2);

        AnnotationCoverageDashboard dashboard = new AnnotationCoverageDashboard();
        Set<String> annotatedMethods = Set.of(
            BridgeRoot.class.getSimpleName() + "/annotatedOne",
            BridgeRoot.class.getSimpleName() + "/annotatedTwo");

        HotspotAlignmentReport.Report r =
            HotspotInventoryBridge.report(rebuilt, annotatedMethods, dashboard);

        assertNotNull(r);
        // 2 already-annotated + 2 hotspots remain to align.
        // Gap should include the SomeEntity.tick row.
        assertTrue(r.gapCount() >= 1);
        assertTrue(r.gaps().stream().anyMatch(g ->
                g.className().equals("SomeEntity") && g.methodName().equals("tick")),
            "expected SomeEntity.tick in the gap list");

        // Dashboard should now have at least one subsystem row keyed on the
        // hotspot class. The simple class name is the subsystem key.
        List<SubsystemCoverage> subsystems = dashboard.subsystems();
        assertTrue(subsystems.stream().anyMatch(c ->
                (c.subsystem().equals(BridgeRoot.class.getSimpleName())
                    || c.subsystem().equals("BridgeRoot"))
                && c.totalHotspotMethods() >= 2),
            "dashboard must contain a BridgeRoot row with ≥2 hotspots");
    }

    @Test
    void subsystemKeyStripsPackageAndLeadingUnderscore() {
        assertEquals("Entity", HotspotInventoryBridge.subsystemKey("net.minecraft.world.entity.Entity"));
        assertEquals("Entity", HotspotInventoryBridge.subsystemKey("Entity"));
        assertEquals("Something", HotspotInventoryBridge.subsystemKey("_Something"));
    }

    @Test
    void convenienceOverloadUsesBridgeScannerAnnotatedSet(@TempDir Path tmp) throws IOException {
        // Hotspot with one already-annotated method + one gap.
        Path profile = tmp.resolve("profile.collapsed");
        Files.writeString(profile,
            BridgeRoot.class.getSimpleName() + ".annotatedOne 5\n"
            + "Mystery.method 3\n");

        MethodHotspotList list = new MethodHotspotList();
        HotspotReport hotspots = list.fromCollapsed(profile, 5);

        AnnotationCoverageDashboard dashboard = new AnnotationCoverageDashboard();
        List<BridgeAnnotationScanner.ScanTarget> targets = List.of(
            BridgeAnnotationScanner.ScanTarget.of(
                BridgeRoot.class.getSimpleName(), BridgeRoot.class));

        HotspotAlignmentReport.Report r =
            HotspotInventoryBridge.report(hotspots, targets, dashboard);

        // 1 annotated (annotatedOne) + 1 gap (Mystery.method)
        assertEquals(1, r.annotatedCount());
        assertEquals(1, r.gapCount());
        assertEquals("Mystery/method", r.gaps().get(0).displayName());

        // Dashboard received the BridgeRoot subsystem row.
        SubsystemCoverage row = dashboard.subsystems().stream()
            .filter(c -> c.subsystem().equals(BridgeRoot.class.getSimpleName()))
            .findFirst().orElseThrow();
        assertTrue(row.totalHotspotMethods() >= 1);
    }
}
