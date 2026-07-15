package org.nebula.maintenance;

import org.nebula.annotations.NebulaRW;
import org.nebula.maintenance.BridgeAnnotationScanner.ScanTarget;
import org.nebula.maintenance.BridgeAnnotationScanner.SubsystemCoverage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * End-to-end wiring for the real method-level hotspot inventory
 * (NEBULA-PATCH-2026-001 §14.3.5 component C; B8 C5 follow-on).
 *
 * <p>The previous slice built {@link MethodHotspotList} (parser) and
 * {@link HotspotAlignmentReport} (priority gap report) as separate pieces,
 * but neither fed {@link AnnotationCoverageDashboard} from a real profile —
 * the dashboard was populated only by {@code BridgeAnnotationScanner}
 * reflections on the bridge classes. This class is the missing link:
 *
 * <ol>
 *   <li>Reads a {@link MethodHotspotList.HotspotReport} (the parsed
 *       async-profiler output).</li>
 *   <li>Aligns it against an externally-known annotated set (e.g. the
 *       {@code BridgeAnnotationScanner} output) to drop the rows that
 *       are already covered.</li>
 *   <li>Feeds the dashboard with per-subsystem coverage derived from the
 *       hotspot's class+method grouping, so the {@code /nebula coverage}
 *       surface can show both the "public bridge methods" denominator
 *       (the existing surface) and the "real hot methods" denominator
 *       (this new surface).</li>
 * </ol>
 *
 * <p>Each hotspot method becomes one {@code SubsystemCoverage} row keyed
 * by the hotspot's {@code Class/method} pair, with {@code totalHotspotMethods=1}.
 * The dashboard's overall coverage ratio then reflects the real-hot
 * fraction, and the gap list drives the operator's annotation queue.
 */
public final class HotspotInventoryBridge {

    private HotspotInventoryBridge() {}

    /**
     * Reports a {@link MethodHotspotList.HotspotReport} into the dashboard,
     * one row per distinct {@code Class/method} pair, with annotated rows
     * collapsed into the coverage numerator and gap rows exposed via the
     * returned alignment report.
     *
     * <p>Rows with an empty class name (native frames, unparseable methods)
     * are dropped — they have no class to attach a {@code @NebulaRW} to.
     *
     * @param hotspots the parsed async-profiler output
     * @param annotatedMethods pre-known annotated set (typically from
     *                         {@code BridgeAnnotationScanner}) — rows in this
     *                         set are counted as covered
     * @param dashboard the dashboard to feed
     * @return the alignment report; its summary is the {@code /nebula
     *         coverage} one-liner for the real-hot denominator
     */
    public static HotspotAlignmentReport.Report report(
        MethodHotspotList.HotspotReport hotspots,
        Set<String> annotatedMethods,
        AnnotationCoverageDashboard dashboard
    ) {
        Objects.requireNonNull(hotspots, "hotspots");
        Objects.requireNonNull(annotatedMethods, "annotatedMethods");
        Objects.requireNonNull(dashboard, "dashboard");

        HotspotAlignmentReport aligner = new HotspotAlignmentReport();
        HotspotAlignmentReport.Report report = aligner.align(hotspots, annotatedMethods);

        // Feed the dashboard one row per distinct subsystem in the hotspot
        // list. We derive the subsystem key from the class's simple name
        // (the last dot-separated segment) so all hotspot rows belonging to
        // the same class group under one bucket — the brief's per-subsystem
        // coverage view depends on this grouping.
        java.util.Map<String, int[]> perSubsystem = new java.util.LinkedHashMap<>();
        for (MethodHotspotList.HotspotMethod h : hotspots.methods()) {
            if (h.className().isEmpty()) continue;
            String key = h.className() + "/" + h.methodName();
            String subsystem = subsystemKey(h.className());
            int[] counters = perSubsystem.computeIfAbsent(subsystem, _k -> new int[2]);
            counters[0]++; // total
            if (h.annotated() || annotatedMethods.contains(key)) {
                counters[1]++; // annotated
            }
        }
        for (var entry : perSubsystem.entrySet()) {
            int annotated = entry.getValue()[1];
            int total = entry.getValue()[0];
            if (total == 0) continue;
            dashboard.report(new AnnotationCoverageDashboard.SubsystemCoverage(
                entry.getKey(), annotated, total, 0));
        }
        return report;
    }

    /**
     * Convenience overload that builds the annotated set from a
     * {@code BridgeAnnotationScanner} target list and reports into the
     * dashboard. Use this from the plugin's coverage surface so the
     * "/nebula coverage" command shows the real-hot fraction without the
     * caller having to thread the scanner's set through by hand.
     */
    public static HotspotAlignmentReport.Report report(
        MethodHotspotList.HotspotReport hotspots,
        List<ScanTarget> scanTargets,
        AnnotationCoverageDashboard dashboard
    ) {
        Set<String> annotated = BridgeAnnotationScanner
            .getSubsystemCoverage(scanTargets).stream()
            .flatMap(row -> annotatedKeysFor(row, scanTargets))
            .collect(Collectors.toUnmodifiableSet());
        return report(hotspots, annotated, dashboard);
    }

    /**
     * Derive the dashboard subsystem key from a class name. We use the
     * simple class name so {@code net.minecraft.world.entity.Entity.tick}
     * and {@code Entity.tick} map to the same subsystem. The class's
     * package prefix carries no signal here — the dashboard already
     * separates subsystems by purpose, not by FQCN.
     */
    public static String subsystemKey(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        // Strip any leading underscore that some internal classes carry.
        return simple.startsWith("_") ? simple.substring(1) : simple;
    }

    private static Stream<String> annotatedKeysFor(
        SubsystemCoverage row,
        List<ScanTarget> targets
    ) {
        // For each target contributing to this subsystem, expose the
        // public-instance method names that carry @NebulaRW. We
        // intentionally return at most the class/method pairs that the
        // scanner's reflection already proved annotated — the hotspot
        // aligner uses these as the "covered" set.
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (ScanTarget t : targets) {
            if (!t.subsystem().equals(row.subsystem())) continue;
            for (Method m : t.root().getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (!(Modifier.isPublic(mods) && !Modifier.isStatic(mods))) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.isAnnotationPresent(NebulaRW.class)) {
                    keys.add(t.root().getSimpleName() + "/" + m.getName());
                }
            }
        }
        return keys.stream();
    }
}
