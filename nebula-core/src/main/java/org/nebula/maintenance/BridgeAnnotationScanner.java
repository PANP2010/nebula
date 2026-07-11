package org.nebula.maintenance;

import org.nebula.annotations.NebulaRW;
import org.nebula.maintenance.AnnotationCoverageDashboard.SubsystemCoverage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * B8 RW-coverage slice (DG3): scans bridge classes for {@link NebulaRW}-annotated
 * methods and feeds {@link AnnotationCoverageDashboard} from a real inventory.
 *
 * <p>The brief distinguishes two RW representations: the hand-built
 * {@code RWSet} objects in {@code *TaskFactory} (the live path), and the
 * method-level annotations that are the whitepaper §14.3.5 source of truth.
 * The NMS patches apply {@code @NebulaRW} on NMS classes (which are NOT in the
 * runtime classpath). For runtime introspection we scan the bridge classes
 * that DO ship on the runtime classpath — {@code NmsBlockStateBridge},
 * {@code NmsBlockEntityStateBridge}, {@code NmsFluidStateBridge},
 * {@code NmsEntityStateBridge}, etc.
 *
 * <p><b>Inventory source.</b> For each subsystem the "total hotspot methods"
 * is the count of public, non-synthetic, declared methods on the bridge root
 * class. The annotated count is the subset of those methods carrying
 * {@link NebulaRW}. Per the patch-001 decay goal (&lt;5%/year) the dashboard
 * treats the annotated/total ratio as the subsystem's coverage.
 *
 * <p><b>Per-subsystem buckets.</b> B8 C5: a subsystem is "redstone",
 * "block-entity", "fluid", "entity", etc. Each subsystem has one root bridge
 * class (and optionally peer bridge classes — e.g. {@code
 * NmsBlockEntityStateBridge} peers with {@code NmsFluidStateBridge} for the
 * shared {@code *WorldState} CAS store). {@link #getSubsystemCoverage(List)}
 * returns one {@link SubsystemCoverage} per subsystem; the dashboard is
 * populated by passing each to {@link AnnotationCoverageDashboard#report}.
 */
public final class BridgeAnnotationScanner {

    private BridgeAnnotationScanner() {}

    /** Per-subsystem scan target: a root bridge class plus optional peers. */
    public record ScanTarget(String subsystem, Class<?> root, List<Class<?>> peers) {
        public ScanTarget {
            Objects.requireNonNull(subsystem, "subsystem");
            Objects.requireNonNull(root, "root");
            peers = peers == null ? List.of() : List.copyOf(peers);
        }

        public static ScanTarget of(String subsystem, Class<?> root) {
            return new ScanTarget(subsystem, root, List.of());
        }
    }

    /**
     * Reflected coverage row for a single subsystem. Mirrors the dashboard's
     * {@link SubsystemCoverage} but uses {@code totalBridgeMethods} to make the
     * denominator's source explicit — public methods on the bridge class are the
     * inventory, NOT a hand-typed number.
     */
    public record SubsystemCoverage(
        String subsystem,
        int annotatedMethods,
        int totalBridgeMethods
    ) {
        public SubsystemCoverage {
            if (annotatedMethods < 0 || totalBridgeMethods < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
            if (annotatedMethods > totalBridgeMethods) {
                throw new IllegalArgumentException("annotated cannot exceed total bridge methods");
            }
        }

        /** Coverage ratio in [0,1]; 1.0 when there are no bridge methods. */
        public double coverageRatio() {
            return totalBridgeMethods == 0 ? 1.0 : (double) annotatedMethods / totalBridgeMethods;
        }
    }

    /**
     * Scan each target's root + peers and return one {@link SubsystemCoverage} per
     * subsystem. The total hotspot count is the public-method count on those
     * bridge classes, so the coverage ratio reflects "what fraction of the public
     * bridge surface has been annotated", not "what fraction of the universe has
     * been catalogued". The honest scope is recorded on the dashboard's summary
     * (see {@link AnnotationCoverageDashboard}).
     */
    public static List<SubsystemCoverage> getSubsystemCoverage(List<ScanTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (ScanTarget t : targets) {
            counts.computeIfAbsent(t.subsystem(), k -> new int[2]);
        }
        for (ScanTarget t : targets) {
            int[] c = counts.get(t.subsystem());
            int annotated = scanClassAnnotated(t.root());
            for (Class<?> peer : t.peers()) {
                annotated += scanClassAnnotated(peer);
            }
            c[0] += annotated;
            int total = countPublicDeclared(t.root());
            for (Class<?> peer : t.peers()) {
                total += countPublicDeclared(peer);
            }
            c[1] += Math.max(total, annotated);
        }

        List<SubsystemCoverage> result = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            String subsystem = entry.getKey();
            int annotated = entry.getValue()[0];
            int total = entry.getValue()[1];
            if (total == 0) continue;
            result.add(new SubsystemCoverage(subsystem, annotated, total));
        }
        return result;
    }

    /**
     * Convenience: scan a single target and return its coverage row (or empty
     * if the target has no public methods).
     */
    public static SubsystemCoverage getSubsystemCoverage(ScanTarget target) {
        List<SubsystemCoverage> rows = getSubsystemCoverage(List.of(target));
        return rows.isEmpty() ? new SubsystemCoverage(target.subsystem(), 0, 0) : rows.get(0);
    }

    /**
     * Scan each target's root + peers for public, non-synthetic declared
     * instance methods and report per-subsystem coverage to the dashboard.
     * This is the legacy entry point kept for callers that already have a
     * dashboard instance; new code should prefer
     * {@link #getSubsystemCoverage(List)} + {@link
     * AnnotationCoverageDashboard#report} so the reflected counts flow through
     * the dashboard with no hand-typed denominator.
     */
    public static void scan(List<ScanTarget> targets, AnnotationCoverageDashboard dashboard) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(dashboard, "dashboard");
        for (SubsystemCoverage sc : getSubsystemCoverage(targets)) {
            dashboard.report(new AnnotationCoverageDashboard.SubsystemCoverage(
                sc.subsystem(), sc.annotatedMethods(), sc.totalBridgeMethods(), 0));
        }
    }

    /** Convenience overload for a single target. */
    public static void scan(ScanTarget target, AnnotationCoverageDashboard dashboard) {
        scan(List.of(target), dashboard);
    }

    private static int scanClassAnnotated(Class<?> cls) {
        int annotated = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!isPublicInstance(m)) continue;
            if (m.isAnnotationPresent(NebulaRW.class)) {
                annotated++;
            }
        }
        return annotated;
    }

    private static int countPublicDeclared(Class<?> cls) {
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (isPublicInstance(m)) n++;
        }
        return n;
    }

    private static boolean isPublicInstance(Method m) {
        int mods = m.getModifiers();
        if (!(Modifier.isPublic(mods) && !Modifier.isStatic(mods))) return false;
        return !m.isSynthetic() && !m.isBridge();
    }
}
