package org.nebula.maintenance;

import org.nebula.maintenance.MethodHotspotList.HotspotMethod;
import org.nebula.maintenance.MethodHotspotList.HotspotReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P1.5.4b hotspot-alignment report — compares a
 * {@link HotspotReport} against the project's {@code @NebulaRW}
 * annotation coverage to surface the "PRIORITY GAP" rows an operator
 * should annotate next.
 *
 * <p>Priority bands (per the P1.5 brief):
 * <ul>
 *   <li><b>HIGH</b> — cumulative CPU &gt; 5%; these are the methods
 *       where the marginal cost of an incomplete {@code @NebulaRW}
 *       declaration is the largest;</li>
 *   <li><b>MEDIUM</b> — 1% ≤ CPU ≤ 5%; still worth annotating but the
 *       blast radius of a missing field is smaller;</li>
 *   <li><b>LOW</b> — &lt; 1%; mostly cleanup. A future cycle can sweep
 *       these in batch once a {@code -DWALK_ANNOTATION_LAZY=true} flag
 *       lets the runtime guard tolerate a longer minimum distance.</li>
 * </ul>
 *
 * <p>Cross-reference sources accepted:
 * <ul>
 *   <li>The {@link HotspotReport}'s own {@code annotated} flag, which
 *       {@link MethodHotspotList} fills in by reflecting over the
 *       project's bridge classes for {@code @NebulaRW} — preferred.</li>
 *   <li>An external {@code Set<String> annotatedMethods} keyed by
 *       {@code "class/method"} — useful for callers that already have
 *       a {@link BridgeAnnotationScanner}-derived set and want to
 *       avoid re-reflection.</li>
 * </ul>
 */
public final class HotspotAlignmentReport {

    /** CPU-share threshold for the HIGH priority band. */
    public static final double HIGH_THRESHOLD = 5.0;
    /** CPU-share threshold for the MEDIUM priority band. */
    public static final double MEDIUM_THRESHOLD = 1.0;

    /** Visible for testing. */
    HotspotAlignmentReport() {}

    /**
     * One gap row — a hotspot method that lacks a {@code @NebulaRW}
     * declaration. Sorted by descending CPU in
     * {@link #align(HotspotReport, Set)} so the operator's eye lands
     * on the highest-impact rows first.
     */
    public record Gap(
        String className,
        String methodName,
        double cpuPercent,
        String priority
    ) {
        public Gap {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(priority, "priority");
            if (cpuPercent < 0.0 || cpuPercent > 100.0) {
                throw new IllegalArgumentException(
                    "cpuPercent out of [0,100]: " + cpuPercent);
            }
        }

        /** Convenience: returns {@code "Class/method"}. */
        public String displayName() {
            return className + "/" + methodName;
        }
    }

    /**
     * The full alignment report: header metadata, sorted gap rows, and
     * a one-line coverage summary suitable for printing in
     * {@code /nebula coverage}.
     */
    public record Report(
        Instant generated,
        int topN,
        int annotatedCount,
        int gapCount,
        List<Gap> gaps
    ) {
        public Report {
            Objects.requireNonNull(generated, "generated");
            Objects.requireNonNull(gaps, "gaps");
            if (topN <= 0) throw new IllegalArgumentException("topN must be positive");
            if (annotatedCount < 0) throw new IllegalArgumentException("annotatedCount");
            if (gapCount < 0) throw new IllegalArgumentException("gapCount");
        }

        /**
         * The {@code "/nebula coverage"} one-liner. Format matches the
         * brief: annotated count, total, percent, and the gap count.
         */
        public String summary() {
            int total = annotatedCount + gapCount;
            double pct = total == 0 ? 100.0 : 100.0 * annotatedCount / total;
            return String.format(
                "Coverage: %d/%d hotspot methods annotated (%.1f%%), Gap: %d methods",
                annotatedCount, total, pct, gapCount);
        }
    }

    /**
     * Aligns a {@link HotspotReport} against the project's
     * {@code @NebulaRW} coverage. Two gaps are filtered out:
     * <ol>
     *   <li>Methods already flagged {@code annotated=true} by
     *       {@link MethodHotspotList} (preferred path);</li>
     *   <li>Methods whose {@code "class/method"} key is present in
     *       {@code annotatedMethods} (fallback for callers with a
     *       precomputed set).</li>
     * </ol>
     * The remaining rows are sorted by descending CPU and assigned a
     * priority band by {@link #priorityFor(double)}.
     */
    public Report align(HotspotReport hotspots, Set<String> annotatedMethods) {
        Objects.requireNonNull(hotspots, "hotspots");
        Objects.requireNonNull(annotatedMethods, "annotatedMethods");
        Set<String> annotated = annotatedMethods.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());

        List<Gap> gaps = new ArrayList<>();
        int annotatedCount = 0;
        for (HotspotMethod h : hotspots.methods()) {
            String key = h.className() + "/" + h.methodName();
            if (h.annotated() || annotated.contains(key)) {
                annotatedCount++;
                continue;
            }
            if (h.className().isEmpty()) {
                // Native frame or unparseable method — skip; no class
                // to attach a @NebulaRW to.
                continue;
            }
            gaps.add(new Gap(
                h.className(), h.methodName(),
                h.cumulativePercent(), priorityFor(h.cumulativePercent())));
        }
        gaps.sort(Comparator
            .comparingDouble(Gap::cpuPercent).reversed()
            .thenComparing(Gap::displayName));
        return new Report(
            Instant.now(), hotspots.topN(),
            annotatedCount, gaps.size(), List.copyOf(gaps));
    }

    /** Maps a CPU percentage to a priority band. */
    public static String priorityFor(double cpuPercent) {
        if (cpuPercent > HIGH_THRESHOLD) return "HIGH";
        if (cpuPercent >= MEDIUM_THRESHOLD) return "MEDIUM";
        return "LOW";
    }
}
