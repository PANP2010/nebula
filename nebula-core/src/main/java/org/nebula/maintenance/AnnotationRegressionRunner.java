package org.nebula.maintenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation regression runner (NEBULA-PATCH-2026-001 §变更一, §14.3.5 组件 B).
 *
 * <p>The patch requires that annotations cannot "silently fail" when Minecraft
 * code changes. This runner is the CI gate: it cross-references the declared
 * annotation library against the method signatures actually present in the
 * target decompiled source. For each annotated method reference it reports one
 * of:
 *
 * <ul>
 *   <li>{@link Status#PRESENT} — the annotated method exists in the source. The
 *       annotation is still anchored to a real method.</li>
 *   <li>{@link Status#MISSING} — no method with that owner+name exists. The
 *       annotation has gone stale (renamed/removed upstream) and would silently
 *       fail; CI must flag it.</li>
 *   <li>{@link Status#AMBIGUOUS} — multiple overloads share the owner+name and
 *       the annotation reference doesn't disambiguate by parameter types; a
 *       human should confirm which overload is intended.</li>
 * </ul>
 *
 * <p>Annotation references use the {@code "ClassSimpleName.methodName"} form the
 * redstone annotation library uses (e.g. {@code "RedStoneWireBlock.neighborChanged"}),
 * matched against extracted {@link MethodSignature}s by simple class name + method
 * name. This is the source-level integrity check; the runtime
 * {@code RWSetConsistencyChecker} (§12.4) is the complementary dynamic check.
 */
public final class AnnotationRegressionRunner {

    public enum Status { PRESENT, MISSING, AMBIGUOUS }

    /** Result for one annotated method reference. */
    public record MethodResult(String annotationRef, Status status, int matchCount) {}

    /** Full regression report over an annotation library. */
    public record RegressionReport(List<MethodResult> results) {
        public RegressionReport {
            results = List.copyOf(results);
        }

        public List<MethodResult> failures() {
            return results.stream()
                .filter(r -> r.status() != Status.PRESENT)
                .collect(Collectors.toList());
        }

        public boolean passed() {
            return results.stream().allMatch(r -> r.status() == Status.PRESENT);
        }

        public long countOf(Status status) {
            return results.stream().filter(r -> r.status() == status).count();
        }

        public String summary() {
            return String.format(
                "Annotation regression: %d refs, %d present, %d missing, %d ambiguous — %s",
                results.size(), countOf(Status.PRESENT), countOf(Status.MISSING),
                countOf(Status.AMBIGUOUS), passed() ? "PASS" : "FAIL");
        }
    }

    private AnnotationRegressionRunner() {}

    /**
     * Runs the regression check.
     *
     * @param annotationRefs method references from the annotation library, in
     *                       {@code "ClassSimpleName.methodName"} form
     * @param sourceSignatures all method signatures extracted from the target
     *                         decompiled source set
     * @return a report with one result per reference
     */
    public static RegressionReport run(List<String> annotationRefs,
                                       List<MethodSignature> sourceSignatures) {
        return run(annotationRefs, sourceSignatures, Map.of());
    }

    /**
     * Inheritance-aware regression check. A reference {@code "Leaf.method"}
     * resolves if {@code method} exists on {@code Leaf} <em>or any ancestor</em>
     * reachable via {@code superclasses} (simple-name → simple-name). This
     * matches how annotation libraries legitimately reference an inherited tick
     * method by its leaf component class (e.g. {@code RepeaterBlock.tick} where
     * {@code tick} is declared on {@code DiodeBlock}).
     *
     * @param superclasses simple-name → simple superclass-name map; methods are
     *                     resolved by walking this chain when the leaf lacks them
     */
    public static RegressionReport run(List<String> annotationRefs,
                                       List<MethodSignature> sourceSignatures,
                                       Map<String, String> superclasses) {
        // Index "SimpleClassName.methodName" → match count.
        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        for (MethodSignature sig : sourceSignatures) {
            String key = simpleName(sig.ownerClass()) + "." + sig.name();
            matchCounts.merge(key, 1, Integer::sum);
        }

        List<MethodResult> results = new ArrayList<>();
        for (String ref : annotationRefs) {
            int count = resolve(ref, matchCounts, superclasses);
            Status status = switch (Integer.signum(count)) {
                case 0 -> Status.MISSING;
                default -> count == 1 ? Status.PRESENT : Status.AMBIGUOUS;
            };
            results.add(new MethodResult(ref, status, count));
        }
        return new RegressionReport(results);
    }

    /**
     * Resolves a {@code "Class.method"} ref to a match count, walking the
     * superclass chain if the leaf class doesn't declare the method.
     */
    private static int resolve(String ref, Map<String, Integer> matchCounts,
                               Map<String, String> superclasses) {
        int dot = ref.indexOf('.');
        if (dot < 0) {
            return matchCounts.getOrDefault(ref, 0);
        }
        String cls = ref.substring(0, dot);
        String method = ref.substring(dot + 1);

        // Walk leaf → ancestors until a class declares the method (cycle-guarded).
        Set<String> seen = new java.util.HashSet<>();
        String current = cls;
        while (current != null && seen.add(current)) {
            int count = matchCounts.getOrDefault(current + "." + method, 0);
            if (count > 0) {
                return count;
            }
            current = superclasses.get(current);
        }
        return 0;
    }

    /** Distinct annotation refs, de-duplicated and order-preserved. */
    public static List<String> distinct(List<String> refs) {
        return refs.stream().distinct().collect(Collectors.toList());
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /** Convenience: are all the given refs present in the source set? */
    public static boolean allPresent(List<String> refs, List<MethodSignature> sigs, Set<String> ignore) {
        List<String> filtered = refs.stream().filter(r -> !ignore.contains(r)).collect(Collectors.toList());
        return run(filtered, sigs).passed();
    }
}
