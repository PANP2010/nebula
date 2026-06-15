package org.nebula.redstone.annotations;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.AnnotationCoverageDashboard;
import org.nebula.maintenance.AnnotationCoverageDashboard.SubsystemCoverage;
import org.nebula.maintenance.AnnotationRegressionRunner;
import org.nebula.maintenance.AnnotationRegressionRunner.RegressionReport;
import org.nebula.maintenance.AnnotationRegressionRunner.Status;
import org.nebula.maintenance.JavaSignatureExtractor;
import org.nebula.maintenance.MethodSignature;
import org.nebula.redstone.RedstoneComponentType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Phase 1.5 annotation-maintenance pipeline against the real
 * {@link RedstoneAnnotations} library and decompiled MC 1.21.4 sources
 * (NEBULA-PATCH-2026-001 §变更一: 组件 A extractor + 组件 B regression runner
 * feeding 组件 C coverage dashboard).
 *
 * <p>This is the integration that ties the maintenance subsystem to a real
 * annotation asset: it pulls every method reference the redstone annotation
 * library declares, checks each against the actual decompiled source, and
 * builds the coverage dashboard from the result.
 */
class RedstoneAnnotationMaintenanceTest {

    /** The decompiled block classes bundled as fixtures (the subset we can verify). */
    private static final Set<String> BUNDLED_CLASSES = Set.of(
        "RepeaterBlock", "DiodeBlock", "RedStoneWireBlock", "RedstoneTorchBlock", "ComparatorBlock");

    private static List<MethodSignature> bundledSignatures() throws IOException {
        List<MethodSignature> all = new ArrayList<>();
        for (String cls : BUNDLED_CLASSES) {
            all.addAll(JavaSignatureExtractor.extract(fixtureSource(cls)));
        }
        return all;
    }

    /** Simple-name → superclass simple-name map, so inherited methods resolve. */
    private static Map<String, String> bundledSuperclasses() throws IOException {
        Map<String, String> supers = new LinkedHashMap<>();
        for (String cls : BUNDLED_CLASSES) {
            String sup = JavaSignatureExtractor.superclassOf(fixtureSource(cls));
            if (sup != null) {
                supers.put(cls, sup);
            }
        }
        return supers;
    }

    private static String fixtureSource(String cls) throws IOException {
        try (InputStream in = RedstoneAnnotationMaintenanceTest.class
                .getResourceAsStream("/decompiled/" + cls + ".java")) {
            assertNotNull(in, "fixture must be present: " + cls);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every distinct "Class.method" reference declared across all component templates. */
    private static List<String> allDeclaredMethodRefs() {
        Set<String> refs = new LinkedHashSet<>();
        for (var template : RedstoneAnnotations.componentTemplates().values()) {
            refs.addAll(template.methods());
        }
        return new ArrayList<>(refs);
    }

    @Test
    void annotationLibraryDeclaresMethodReferences() {
        List<String> refs = allDeclaredMethodRefs();
        assertFalse(refs.isEmpty(), "the redstone annotation library must declare method refs");
        // Each ref is in "ClassSimpleName.methodName" form.
        for (String ref : refs) {
            assertTrue(ref.contains("."), "malformed method ref: " + ref);
        }
    }

    @Test
    void bundledSubsystemAnnotationsResolveToRealMethods() throws IOException {
        // Restrict to refs whose owning class is one we bundled, then assert
        // every one resolves to a real method in decompiled MC 1.21.4 —
        // resolving inherited methods through the class hierarchy (e.g.
        // RepeaterBlock.tick is declared on the parent DiodeBlock). This is the
        // §14.3.5 组件 B guarantee for the verifiable subset.
        List<MethodSignature> sigs = bundledSignatures();
        Map<String, String> supers = bundledSuperclasses();
        List<String> refs = allDeclaredMethodRefs().stream()
            .filter(r -> BUNDLED_CLASSES.contains(r.substring(0, r.indexOf('.'))))
            .toList();
        assertFalse(refs.isEmpty(), "expected some refs into the bundled classes");

        RegressionReport report = AnnotationRegressionRunner.run(refs, sigs, supers);
        List<AnnotationRegressionRunner.MethodResult> missing = report.results().stream()
            .filter(r -> r.status() == Status.MISSING)
            .toList();
        assertTrue(missing.isEmpty(),
            "bundled-subsystem annotations must resolve to real methods (incl. inherited); missing="
                + missing + " (" + report.summary() + ")");
    }

    @Test
    void coverageDashboardBuiltFromRealScan() throws IOException {
        // 组件 C fed from a real scan: for each bundled redstone component, count
        // how many of its declared method refs resolve to real source methods,
        // and report that as subsystem coverage.
        List<MethodSignature> sigs = bundledSignatures();
        Map<String, String> supers = bundledSuperclasses();
        AnnotationCoverageDashboard dashboard = new AnnotationCoverageDashboard();

        for (var entry : RedstoneAnnotations.componentTemplates().entrySet()) {
            RedstoneComponentType type = entry.getKey();
            List<String> refs = entry.getValue().methods().stream()
                .filter(r -> BUNDLED_CLASSES.contains(r.substring(0, r.indexOf('.'))))
                .toList();
            if (refs.isEmpty()) {
                continue; // component's classes aren't in the bundled fixture set
            }
            RegressionReport report = AnnotationRegressionRunner.run(refs, sigs, supers);
            int resolved = (int) (report.countOf(Status.PRESENT) + report.countOf(Status.AMBIGUOUS));
            dashboard.report(new SubsystemCoverage(type.name(), resolved, refs.size(), 0));
        }

        assertFalse(dashboard.subsystems().isEmpty(), "dashboard must have scanned subsystems");
        assertTrue(dashboard.overallCoverageRatio() > 0.0);
        assertTrue(dashboard.meetsDecayTarget(dashboard.overallCoverageRatio()),
            "freshly-scanned coverage trivially meets its own baseline");
        assertTrue(dashboard.summary().contains("coverage"));
    }
}
