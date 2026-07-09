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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    /**
     * Method-ref constants in {@link RedstoneAnnotations} that are deliberately
     * NOT wired into any {@code ComponentTemplate.methods()} list, with the A2
     * audit reason recorded on each field's javadoc (2026-07-09):
     * <ul>
     *   <li>{@code CollectingNeighborUpdater.runNext} — cross-cutting fan-out
     *       infrastructure; its RW fact is the {@code region.neighbor_updater}
     *       global that every firing component already declares.</li>
     *   <li>{@code SculkSensorBlock.tick} — A3 RESOLVED (2026-07-09): its home is
     *       the game-event / vibration subsystem, NOT the redstone DAG (it is
     *       vibration-triggered + scheduled-tick-driven, so the BLOCK_UPDATE-seeded
     *       redstone pipeline can never seed it). Intentionally kept as a reference
     *       constant with no {@code RedstoneComponentType} — see the field javadoc
     *       on {@code RedstoneAnnotations.SCULK_SENSOR_TICK} for the full rationale.</li>
     * </ul>
     * Anything else that is declared-but-unreferenced is an accidental orphan and
     * must fail {@link #everyDeclaredMethodConstantIsEitherWiredOrExplicitlyUnwired}.
     */
    private static final Set<String> KNOWN_UNWIRED_METHOD_REFS = Set.of(
        "CollectingNeighborUpdater.runNext",
        "SculkSensorBlock.tick");

    /** Every {@code public static final String} method-ref constant declared on the library. */
    private static Set<String> declaredMethodConstants() {
        Set<String> constants = new TreeSet<>();
        for (Field f : RedstoneAnnotations.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && f.getType() == String.class) {
                try {
                    constants.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("could not read constant " + f.getName(), e);
                }
            }
        }
        return constants;
    }

    @Test
    void everyDeclaredMethodConstantIsEitherWiredOrExplicitlyUnwired() {
        // A2 orphan guard (2026-07-09): a method-ref constant is either referenced
        // by some ComponentTemplate.methods() list (wired into the live per-component
        // metadata) or explicitly enumerated in KNOWN_UNWIRED_METHOD_REFS with a
        // recorded reason. A new constant that is neither is an accidental orphan —
        // exactly the doc-vs-path drift this project treats as its defining wound —
        // and fails the build here until it is folded in or justified.
        Set<String> wired = new TreeSet<>(allDeclaredMethodRefs());
        Set<String> orphans = new TreeSet<>();
        for (String constant : declaredMethodConstants()) {
            if (!wired.contains(constant) && !KNOWN_UNWIRED_METHOD_REFS.contains(constant)) {
                orphans.add(constant);
            }
        }
        assertTrue(orphans.isEmpty(),
            "orphaned method-ref constants (fold into the owning ComponentTemplate or add to "
                + "KNOWN_UNWIRED_METHOD_REFS with a reason): " + orphans);

        // Keep the allow-list honest: every KNOWN_UNWIRED ref must actually exist as
        // a declared constant, so a renamed/removed constant can't leave a stale entry.
        Set<String> declared = declaredMethodConstants();
        for (String unwired : KNOWN_UNWIRED_METHOD_REFS) {
            assertTrue(declared.contains(unwired),
                "KNOWN_UNWIRED_METHOD_REFS names a constant that no longer exists: " + unwired);
        }
    }

    @Test
    void foldedSignalConstantsAreWiredIntoTheirOwningTemplates() {
        // Pins the A2 folding decision: the five getSignal-family constants that were
        // orphaned before 2026-07-09 are now referenced by their owning component's
        // template. This is a behavioural assertion, not just a count.
        Map<String, RedstoneComponentType> expected = new LinkedHashMap<>();
        expected.put(RedstoneAnnotations.WIRE_GET_SIGNAL, RedstoneComponentType.REDSTONE_WIRE);
        expected.put(RedstoneAnnotations.WIRE_GET_DIRECT_SIGNAL, RedstoneComponentType.REDSTONE_WIRE);
        expected.put(RedstoneAnnotations.WIRE_TURBO_SHAPE, RedstoneComponentType.REDSTONE_WIRE);
        expected.put(RedstoneAnnotations.REPEATER_GET_SIGNAL, RedstoneComponentType.REPEATER);
        expected.put(RedstoneAnnotations.WEIGHTED_PRESSURE_PLATE_SIGNAL_FOR_STATE,
            RedstoneComponentType.PRESSURE_PLATE);

        for (var e : expected.entrySet()) {
            List<String> methods = RedstoneAnnotations.componentTemplate(e.getValue()).methods();
            assertTrue(methods.contains(e.getKey()),
                e.getValue() + " template must reference folded constant " + e.getKey()
                    + " (methods=" + methods + ")");
        }
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
