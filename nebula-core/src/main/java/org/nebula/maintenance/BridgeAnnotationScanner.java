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
     * Scan each target's root + peers for public, non-synthetic declared
     * instance methods and report per-subsystem coverage to the dashboard.
     * The total hotspot count is the same surface, so the coverage ratio
     * reflects "what fraction of the public bridge surface has been
     * annotated", not "what fraction of the universe has been catalogued".
     */
    public static void scan(List<ScanTarget> targets, AnnotationCoverageDashboard dashboard) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(dashboard, "dashboard");

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

        for (var entry : counts.entrySet()) {
            String subsystem = entry.getKey();
            int annotated = entry.getValue()[0];
            int total = entry.getValue()[1];
            if (total == 0) continue;
            dashboard.report(new SubsystemCoverage(subsystem, annotated, total, 0));
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