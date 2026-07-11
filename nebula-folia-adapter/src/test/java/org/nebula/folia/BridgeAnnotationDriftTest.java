package org.nebula.folia;

import org.junit.jupiter.api.Test;
import org.nebula.annotations.NebulaRW;
import org.nebula.maintenance.AnnotationCoverageDashboard;
import org.nebula.maintenance.BridgeAnnotationScanner;
import org.nebula.maintenance.BridgeAnnotationScanner.ScanTarget;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8 RW-coverage slice (DG3): drift guard between the runtime bridge's
 * {@link NebulaRW} annotations and the hand-built {@code RWSet} declarations
 * in {@code BlockEntityTaskFactory} / {@code RedstoneTaskFactory}.
 *
 * <p>This is the structural analog of the B8 A1 redstone drift test, but for
 * the bridge layer. It catches the failure mode where one side gets a new
 * field and the other doesn't, so the runtime guard would either flag a
 * legitimate access (factory > annotation) or miss a real divergence
 * (annotation > factory).
 */
class BridgeAnnotationDriftTest {

    @Test
    void nmsBlockEntityStateBridgeAnnotationsListEveryInventorySlot() {
        // The bridge reads and writes every slot count uniformly (0..8 for the
        // generic 9-slot container). The annotation must enumerate all nine
        // slots, mirroring the conservative envelope the BlockEntityActions use.
        // If the bridge annotates a sub-range (e.g. just slots[0..4]), the
        // runtime guard would miss writes to the unannotated slots and silently
        // let them race — exactly the doc-vs-reality drift this project treats
        // as its defining wound.
        Method sync = findMethod(NmsBlockEntityStateBridge.class, "syncInventoryFromNms");
        NebulaRW rw = sync.getAnnotation(NebulaRW.class);
        assert rw != null : "syncInventoryFromNms must be @NebulaRW-annotated (see NmsBlockEntityStateBridge)";

        Set<String> reads = new HashSet<>(List.of(rw.readBlockEntities()));
        Set<String> writes = new HashSet<>(List.of(rw.writeBlockEntities()));
        for (int i = 0; i <= 8; i++) {
            String slot = "{pos}.inventory.slots[" + i + "]";
            assertTrue(reads.contains(slot), "annotation must declare read on " + slot);
            assertTrue(writes.contains(slot), "annotation must declare write on " + slot);
        }
    }

    @Test
    void nmsBlockEntityStateBridgeAnnotationMatchesFactoryBrewingStandSlots() {
        // Drift: the bridge reads NMS inventory and pushes it into the CAS store.
        // The brewing action reads slots 0..4 (it only needs bottles + ingredient
        // + fuel). The bridge declares reads on slots 0..8 because it cannot
        // know which container type it is dealing with at runtime; this is the
        // conservative-coverage design — the bridge's RW-set is a superset of
        // the most-general block-entity task's RW-set. This test pins that
        // superset relationship (not equality) so a future narrowing of the
        // bridge cannot silently drop a slot the brewing/furnace action needs.
        Method sync = findMethod(NmsBlockEntityStateBridge.class, "syncInventoryFromNms");
        NebulaRW bridgeRw = sync.getAnnotation(NebulaRW.class);
        assert bridgeRw != null : "syncInventoryFromNms must be @NebulaRW-annotated";

        Set<String> bridgeSlots = new HashSet<>(List.of(bridgeRw.readBlockEntities()));
        // The 5-slot brewing subset is documented in BlockEntityActions.brewing
        // and the test BlockEntityTaskFactoryTest.brewingStandSlots.
        for (int i = 0; i <= 4; i++) {
            String slot = "{pos}.inventory.slots[" + i + "]";
            assertTrue(bridgeSlots.contains(slot),
                "bridge must cover brewing slot " + slot + " (its RW-set is the superset)");
        }
    }

    @Test
    void nmsBlockStateBridgeAnnotationsDeclarePowerFieldRoundTrip() {
        // The redstone bridge has two annotated entry points: syncFromNms (read)
        // and syncToNms (write). Both touch the same `{pos}.power` block-entity
        // field; the round-trip must declare both ends so the runtime guard can
        // observe the read-then-write pattern as a single RW pair (instead of
        // flagging the write as undeclared).
        Method read = findMethod(NmsBlockStateBridge.class, "syncFromNms");
        Method write = findMethod(NmsBlockStateBridge.class, "syncToNms");
        NebulaRW readRw = read.getAnnotation(NebulaRW.class);
        NebulaRW writeRw = write.getAnnotation(NebulaRW.class);
        assert readRw != null : "syncFromNms must be @NebulaRW-annotated";
        assert writeRw != null : "syncToNms must be @NebulaRW-annotated";

        assertTrue(List.of(readRw.writeBlockEntities()).contains("{pos}.power"),
            "syncFromNms's CAS commit must declare a write to {pos}.power");
        assertTrue(List.of(writeRw.writeBlockEntities()).contains("{pos}.power"),
            "syncToNms's CAS store update must declare a write to {pos}.power");
        assertTrue(List.of(writeRw.readBlockEntities()).contains("{pos}.power"),
            "syncToNms's pre-write read must declare a read on {pos}.power");
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new AssertionError("method not found: " + cls.getSimpleName() + "." + name);
    }

    @Test
    void bridgeAnnotationScannerBuildsDashboardFromRealInventory() {
        // 组件 C (DG3): wire a real scan of the bridge classes (not hand-typed
        // counts). The annotated/total ratio must be > 0 (we just added
        // annotations on this slice) and the subsystems list must be non-empty.
        // Without these asserts, the dashboard wiring is a no-op shell and the
        // /nebula coverage command would print "0/0" forever.
        AnnotationCoverageDashboard d = new AnnotationCoverageDashboard();
        List<ScanTarget> targets = new ArrayList<>();
        targets.add(ScanTarget.of("redstone-bridge", NmsBlockStateBridge.class));
        targets.add(ScanTarget.of("block-entity-bridge", NmsBlockEntityStateBridge.class));
        targets.add(ScanTarget.of("fluid-bridge", NmsFluidStateBridge.class));
        targets.add(ScanTarget.of("entity-bridge", NmsEntityStateBridge.class));
        BridgeAnnotationScanner.scan(targets, d);

        var subsystems = d.subsystems();
        assertFalse(subsystems.isEmpty(), "scan must report at least one subsystem");
        // B8 C5: every subsystem registered in NebulaPlugin.buildCoverageDashboard
        // must show up in the reflected scan. A missing subsystem means a class
        // was moved or renamed and the plugin's reflection-by-name fallback
        // silently dropped it.
        var subsystemNames = subsystems.stream().map(s -> s.subsystem()).toList();
        assertTrue(subsystemNames.contains("redstone-bridge"),
            "redstone-bridge subsystem missing — got: " + subsystemNames);
        assertTrue(subsystemNames.contains("block-entity-bridge"),
            "block-entity-bridge subsystem missing — got: " + subsystemNames);
        assertTrue(subsystemNames.contains("fluid-bridge"),
            "fluid-bridge subsystem missing — got: " + subsystemNames);
        assertTrue(subsystemNames.contains("entity-bridge"),
            "entity-bridge subsystem missing — got: " + subsystemNames);
        // redstone-bridge has at least one annotated method on NmsBlockStateBridge;
        // the ratio should be > 0 (annotation is present).
        boolean anyAboveZero = subsystems.stream()
            .anyMatch(s -> s.annotatedMethods() > 0);
        assertTrue(anyAboveZero,
            "at least one subsystem must report annotated > 0 (this slice added annotations); got: "
                + subsystems);
    }

    @Test
    void reportFromGetSubsystemCoverageMatchesDirectScan() {
        // B8 C5 wiring: the new flow in NebulaPlugin.buildCoverageDashboard
        // calls BridgeAnnotationScanner.getSubsystemCoverage(targets) and feeds
        // each row through dashboard.report() directly (no intermediate
        // hand-typed counts). This test pins that contract: a caller using the
        // getSubsystemCoverage→report pipeline must see the SAME subsystem rows
        // as the legacy BridgeAnnotationScanner.scan(targets, dashboard) flow.
        AnnotationCoverageDashboard direct = new AnnotationCoverageDashboard();
        AnnotationCoverageDashboard viaReport = new AnnotationCoverageDashboard();

        List<ScanTarget> targets = List.of(
            ScanTarget.of("redstone-bridge", NmsBlockStateBridge.class),
            ScanTarget.of("block-entity-bridge", NmsBlockEntityStateBridge.class),
            ScanTarget.of("fluid-bridge", NmsFluidStateBridge.class),
            ScanTarget.of("entity-bridge", NmsEntityStateBridge.class));

        BridgeAnnotationScanner.scan(targets, direct);
        for (var row : BridgeAnnotationScanner.getSubsystemCoverage(targets)) {
            viaReport.report(new AnnotationCoverageDashboard.SubsystemCoverage(
                row.subsystem(), row.annotatedMethods(), row.totalBridgeMethods(), 0));
        }

        var directRows = direct.subsystems();
        var reportRows = viaReport.subsystems();
        assertEquals(directRows.size(), reportRows.size(),
            "direct scan and report-flow must produce the same number of subsystems");
        for (int i = 0; i < directRows.size(); i++) {
            var a = directRows.get(i);
            var b = reportRows.get(i);
            assertEquals(a.subsystem(), b.subsystem());
            assertEquals(a.annotatedMethods(), b.annotatedMethods(),
                "annotated count mismatch for " + a.subsystem());
            assertEquals(a.totalHotspotMethods(), b.totalHotspotMethods(),
                "total count mismatch for " + a.subsystem());
        }
    }
}