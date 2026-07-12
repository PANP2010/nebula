package org.nebula.maintenance;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * P1.5.3d coverage trend analyzer — the chart-backed companion to
 * {@link AnnotationCoverageDashboard}. The dashboard records the current
 * coverage state; this class turns a history of states into the
 * "annotator velocity" the patch-001 decay target (&lt;5%/year) is measured
 * against.
 *
 * <p>Each {@link CoverageSnapshot} is one observation: a timestamp, the
 * Minecraft version it was taken against, the count of {@code @NebulaRW}
 * annotations seen, and the total bridge-method count. The history is
 * persisted as a JSON file by
 * {@link CoverageTrendStore} and reloaded on server start; the analyzer
 * itself is pure — no I/O, no global state — so it can be unit-tested in
 * isolation.
 *
 * <p><b>Decay direction.</b> The patch says "decay rate &lt;5%/year". A
 * positive {@link TrendResult#deltaPercent} means the project's annotator
 * velocity is winning the race against upstream Mojang refactors; a
 * negative number means the assets are decaying. The honest reading is
 * always against the baseline: see
 * {@link AnnotationCoverageDashboard#meetsDecayTarget(double)}.
 */
public final class CoverageTrendAnalyzer {

    private CoverageTrendAnalyzer() {}

    /**
     * One historical observation of annotation coverage.
     *
     * @param timestamp         when the snapshot was taken
     * @param minecraftVersion  MC version (e.g. "1.21.4") the snapshot was
     *                          measured against — useful for detecting
     *                          version transitions in the trend
     * @param totalBridgeMethods  public instance method count on the scanned
     *                            bridge classes (the denominator)
     * @param annotatedMethods  count of methods carrying {@code @NebulaRW}
     *                          (the numerator)
     */
    public record CoverageSnapshot(
        Instant timestamp,
        String minecraftVersion,
        int totalBridgeMethods,
        int annotatedMethods
    ) {
        public CoverageSnapshot {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            if (totalBridgeMethods < 0) {
                throw new IllegalArgumentException("totalBridgeMethods must be non-negative");
            }
            if (annotatedMethods < 0) {
                throw new IllegalArgumentException("annotatedMethods must be non-negative");
            }
            if (annotatedMethods > totalBridgeMethods) {
                throw new IllegalArgumentException(
                    "annotatedMethods (" + annotatedMethods
                        + ") cannot exceed totalBridgeMethods (" + totalBridgeMethods + ")");
            }
        }

        /** Coverage ratio in [0, 1]. Returns 1.0 when the denominator is zero. */
        public double coverageRatio() {
            return totalBridgeMethods == 0
                ? 1.0
                : (double) annotatedMethods / totalBridgeMethods;
        }
    }

    /**
     * Trend result for a sequence of snapshots. {@code deltaPercent} is the
     * week-over-week coverage delta (percentage points, not a relative
     * ratio). {@code methodsAdded}/{@code methodsRemoved} count net
     * annotation changes between the earliest and latest snapshots in
     * {@code history} — they are the annotator-velocity bookends.
     */
    public record TrendResult(
        double currentRatio,
        double deltaPercent,
        int methodsAdded,
        int methodsRemoved
    ) {
        public TrendResult {
            if (currentRatio < 0.0 || currentRatio > 1.0) {
                throw new IllegalArgumentException("currentRatio out of [0,1]: " + currentRatio);
            }
        }

        /**
         * Renders the trend as the operator-facing one-liner the
         * /nebula coverage command prints. Format: a leading "+" or "-"
         * for the delta, two-decimal precision, suffixed with
         * "(annotator velocity)" so the metric reads at a glance.
         */
        public String formatDelta() {
            return String.format("%+.2f%% week-over-week (annotator velocity)", deltaPercent);
        }
    }

    /**
     * Computes the trend from a (possibly unsorted) history. The history
     * is sorted by timestamp ascending internally; the latest snapshot
     * becomes the "current", the one a week (or closest-to-week) earlier
     * becomes the "previous".
     *
     * <p>Edge cases:
     * <ul>
     *   <li>Empty history → returns a zero result ({@code currentRatio=1.0,
     *       deltaPercent=0}, both deltas 0).</li>
     *   <li>Singleton history → returns the single ratio with zero delta.</li>
     *   <li>Multiple snapshots newer than one week → the most recent
     *       snapshot older than {@code latest - 7d} is used as the
     *       "previous"; if none exist, the oldest snapshot is used.</li>
     * </ul>
     */
    public static TrendResult analyze(List<CoverageSnapshot> history) {
        Objects.requireNonNull(history, "history");
        if (history.isEmpty()) {
            return new TrendResult(1.0, 0.0, 0, 0);
        }

        List<CoverageSnapshot> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparing(CoverageSnapshot::timestamp));

        CoverageSnapshot latest = sorted.get(sorted.size() - 1);
        double currentRatio = latest.coverageRatio();

        // Pick "previous": prefer a snapshot ~7d older than latest. If
        // none is older than 7d, fall back to the second-most-recent
        // snapshot; if there is only one snapshot, return zero delta.
        CoverageSnapshot previous;
        if (sorted.size() == 1) {
            return new TrendResult(currentRatio, 0.0, 0, 0);
        }
        Instant target = latest.timestamp().minus(Duration.ofDays(7));
        previous = pickPrevious(sorted, target);

        double previousRatio = previous.coverageRatio();
        double delta = (currentRatio - previousRatio) * 100.0;

        int totalDelta = latest.annotatedMethods() - previous.annotatedMethods();
        int methodsAdded = Math.max(0, totalDelta);
        int methodsRemoved = Math.max(0, -totalDelta);

        return new TrendResult(currentRatio, delta, methodsAdded, methodsRemoved);
    }

    private static CoverageSnapshot pickPrevious(
        List<CoverageSnapshot> sorted, Instant target) {
        // sorted is non-empty with size >= 2 (caller checked).
        // Pick the LATEST snapshot whose timestamp is at or before `target`.
        // The history is ascending, so we walk once from the back and stop
        // at the first one that satisfies the condition.
        CoverageSnapshot candidate = null;
        for (int i = sorted.size() - 2; i >= 0; i--) {
            CoverageSnapshot s = sorted.get(i);
            if (!s.timestamp().isAfter(target)) {
                candidate = s;
                break;
            }
        }
        if (candidate == null) {
            // Nothing in history is older than 7d — fall back to the
            // second-most-recent snapshot.
            candidate = sorted.get(sorted.size() - 2);
        }
        return candidate;
    }
}
