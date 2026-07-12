package org.nebula.maintenance;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1.5.4a method hotspot list — turns an async-profiler flat profile
 * (or a collapsed-format file) into a ranked, cross-referenceable
 * list of methods.
 *
 * <p>The input is one of:
 * <ul>
 *   <li><b>async-profiler {@code -o flat} output</b> — a tabular text file
 *       with a header section ("--- Execution profile ---", "Total
 *       samples"), then rows of {@code ns percent samples top}. This is
 *       the canonical format the project's existing
 *       {@code docs/profiling/flat-profile-*.txt} files use.</li>
 *   <li><b>async-profiler {@code -o collapsed} output</b> — a stack trace
 *       per line, semicolon-separated frames, space-separated trailing
 *       sample count. The {@code top} frame is the leaf method; the
 *       cumulative percentage is derived by aggregating every row that
 *       contains the leaf frame.</li>
 * </ul>
 *
 * <p>Both formats are normalised to {@link HotspotMethod} rows with a
 * {@code className}/{@code methodName}/{@code descriptor} triple and a
 * cumulative percentage. The hotspot list is the input to
 * {@link HotspotAlignmentReport}, which aligns it against the
 * {@code @NebulaRW} coverage to surface "PRIORITY GAP" rows.
 */
public final class MethodHotspotList {

    private static final Pattern FLAT_ROW = Pattern.compile(
        "^\\s*(\\d+)\\s+(\\d+\\.\\d+)%\\s+(\\d+)\\s+(.+?)\\s*$");

    private static final Pattern COLLAPSED_ROW = Pattern.compile("^\\s*(.+?)\\s+(\\d+)\\s*$");

    /** Visible for testing. Production callers use {@link #fromFlamegraph}. */
    MethodHotspotList() {}

    /**
     * A single hotspot method extracted from a profile.
     *
     * @param className          parsed class name (e.g. {@code "Entity"})
     * @param methodName         parsed method name (e.g. {@code "aiStep"})
     * @param descriptor         optional JVM-style descriptor, e.g.
     *                           {@code "()V"}; empty if not known
     * @param cumulativePercent  cumulative CPU share (0-100), rounded
     * @param annotated          whether the project's
     *                           {@code BridgeAnnotationScanner} reports
     *                           this method as carrying
     *                           {@code @NebulaRW}
     * @param annotationSnippet  if {@code annotated} is true, the
     *                           first read field for human display,
     *                           e.g. {@code "@NebulaRW(read={\"entity.position\"})"}
     */
    public record HotspotMethod(
        String className,
        String methodName,
        String descriptor,
        double cumulativePercent,
        boolean annotated,
        String annotationSnippet
    ) {
        public HotspotMethod {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(descriptor, "descriptor");
            if (cumulativePercent < 0.0 || cumulativePercent > 100.0) {
                throw new IllegalArgumentException(
                    "cumulativePercent out of [0,100]: " + cumulativePercent);
            }
            annotationSnippet = annotationSnippet == null ? "" : annotationSnippet;
        }

        /** A readable identifier — {@code "Class/method"}. */
        public String displayName() {
            return className + "/" + methodName;
        }
    }

    /**
     * The full hotspot report: header metadata, the top-N methods, and
     * aggregate coverage/gap counts.
     */
    public record HotspotReport(
        Instant generated,
        String sourceFile,
        int topN,
        List<HotspotMethod> methods,
        int annotatedCount,
        int gapCount
    ) {
        public HotspotReport {
            Objects.requireNonNull(generated, "generated");
            Objects.requireNonNull(sourceFile, "sourceFile");
            Objects.requireNonNull(methods, "methods");
            if (topN <= 0) throw new IllegalArgumentException("topN must be positive");
            if (annotatedCount < 0) throw new IllegalArgumentException("annotatedCount");
            if (gapCount < 0) throw new IllegalArgumentException("gapCount");
            if (annotatedCount + gapCount > methods.size()) {
                throw new IllegalArgumentException(
                    "annotated+gap cannot exceed methods.size()");
            }
        }

        /**
         * Coverage ratio of the hotspot list — annotated / total — in
         * [0, 1]. Returns 1.0 when the list is empty.
         */
        public double coverageRatio() {
            return methods.isEmpty() ? 1.0 : (double) annotatedCount / methods.size();
        }
    }

    /**
     * Reads an async-profiler flat-profile file and produces a top-N
     * hotspot report. Rows with non-Java frames (native methods, JVM
     * internals without a class/method split) are still included but
     * carry an empty {@code className} — the alignment report treats
     * them as unannotated by definition.
     */
    public HotspotReport fromFlatProfile(Path flatProfile, int topN) throws IOException {
        Objects.requireNonNull(flatProfile, "flatProfile");
        if (topN <= 0) throw new IllegalArgumentException("topN must be positive");

        List<HotspotMethod> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(flatProfile);
        for (String line : lines) {
            Matcher m = FLAT_ROW.matcher(line);
            if (!m.matches()) continue;
            int samples = Integer.parseInt(m.group(3));
            double percent = Double.parseDouble(m.group(2));
            String top = m.group(4);
            ParsedName pn = parseMethodName(top);
            rows.add(new HotspotMethod(
                pn.className(), pn.methodName(), pn.descriptor(),
                round2(percent), false, ""));
        }
        // Sort by percent descending, then by name for determinism.
        rows.sort(Comparator
            .comparingDouble(HotspotMethod::cumulativePercent).reversed()
            .thenComparing(HotspotMethod::displayName));
        if (rows.size() > topN) {
            rows = new ArrayList<>(rows.subList(0, topN));
        }
        annotate(rows);
        return buildReport(rows, flatProfile.toString(), topN);
    }

    /**
     * Reads an async-profiler collapsed-format file (one stack trace per
     * line, semicolon-separated frames, trailing sample count). The
     * cumulative percentage for each leaf frame is its share of the
     * total sample count across the file.
     */
    public HotspotReport fromCollapsed(Path collapsed, int topN) throws IOException {
        Objects.requireNonNull(collapsed, "collapsed");
        if (topN <= 0) throw new IllegalArgumentException("topN must be positive");

        Map<String, Long> perLeaf = new java.util.HashMap<>();
        long total = 0;
        for (String line : Files.readAllLines(collapsed)) {
            Matcher m = COLLAPSED_ROW.matcher(line);
            if (!m.matches()) continue;
            String stack = m.group(1);
            long count;
            try {
                count = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            total += count;
            int sep = stack.indexOf(';');
            String leaf = sep < 0 ? stack : stack.substring(0, sep);
            perLeaf.merge(leaf, count, Long::sum);
        }
        if (total == 0) total = 1; // avoid divide-by-zero on an empty profile

        List<HotspotMethod> rows = new ArrayList<>();
        for (var entry : perLeaf.entrySet()) {
            double percent = 100.0 * entry.getValue() / total;
            ParsedName pn = parseMethodName(entry.getKey());
            rows.add(new HotspotMethod(
                pn.className(), pn.methodName(), pn.descriptor(),
                round2(percent), false, ""));
        }
        rows.sort(Comparator
            .comparingDouble(HotspotMethod::cumulativePercent).reversed()
            .thenComparing(HotspotMethod::displayName));
        if (rows.size() > topN) {
            rows = new ArrayList<>(rows.subList(0, topN));
        }
        annotate(rows);
        return buildReport(rows, collapsed.toString(), topN);
    }

    /**
     * Detect the file format by extension and dispatch. {@code .txt} and
     * files containing the {@code --- Execution profile ---} header use
     * the flat-profile parser; {@code .collapsed} files use the
     * collapsed parser. {@code .html} files are detected but
     * <em>not</em> parsed — async-profiler's HTML flamegraph embeds
     * canvas rectangles and is not machine-readable in this slice.
     */
    public HotspotReport fromFlamegraph(Path flamegraph, int topN) throws IOException {
        Objects.requireNonNull(flamegraph, "flamegraph");
        String name = flamegraph.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".collapsed")) {
            return fromCollapsed(flamegraph, topN);
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            throw new IOException(
                "HTML flamegraph parsing is not supported in this slice; "
                    + "re-run async-profiler with -o flat or -o collapsed");
        }
        return fromFlatProfile(flamegraph, topN);
    }

    // ── Annotation cross-reference ─────────────────────────────────────────────

    private static void annotate(List<HotspotMethod> methods) {
        for (int i = 0; i < methods.size(); i++) {
            HotspotMethod h = methods.get(i);
            if (h.className().isEmpty()) continue;
            Optional<AnnotationMatch> match = findAnnotation(h);
            if (match.isEmpty()) continue;
            methods.set(i, new HotspotMethod(
                h.className(), h.methodName(), h.descriptor(),
                h.cumulativePercent(),
                true,
                match.get().snippet()));
        }
    }

    private static Optional<AnnotationMatch> findAnnotation(HotspotMethod h) {
        String className = h.className();
        // Async-profiler top frames frequently include the leading
        // package (e.g. "net.minecraft.world.entity.Entity.tick"). Try
        // the full name first, then the simple class name.
        Class<?> cls = findClass(className).orElse(null);
        if (cls == null) {
            int dot = className.lastIndexOf('.');
            if (dot > 0) {
                cls = findClass(className.substring(dot + 1)).orElse(null);
            }
        }
        if (cls == null) return Optional.empty();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(h.methodName())) continue;
            org.nebula.annotations.NebulaRW rw =
                m.getAnnotation(org.nebula.annotations.NebulaRW.class);
            if (rw == null) continue;
            String snippet = formatSnippet(rw);
            return Optional.of(new AnnotationMatch(snippet));
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> findClass(String name) {
        try {
            return Optional.of(Class.forName(name));
        } catch (ClassNotFoundException | LinkageError e) {
            // Try a few common package prefixes if the bare name is
            // ambiguous.
            String[] prefixes = {
                "net.minecraft.", "io.papermc.paper.", "ca.spottedleaf.",
                "org.bukkit.", "java.", "jdk.internal."
            };
            for (String prefix : prefixes) {
                try {
                    return Optional.of(Class.forName(prefix + name));
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // try next
                }
            }
            return Optional.empty();
        }
    }

    private static String formatSnippet(org.nebula.annotations.NebulaRW rw) {
        String[] reads = rw.readEntities();
        String[] writes = rw.writeEntities();
        String[] blocks = rw.readBlocks();
        if (reads.length > 0) {
            return "@NebulaRW(read={\""
                + String.join(",", reads) + "\"})";
        }
        if (writes.length > 0) {
            return "@NebulaRW(write={\""
                + String.join(",", writes) + "\"})";
        }
        if (blocks.length > 0) {
            return "@NebulaRW(blocks={\""
                + String.join(",", blocks) + "\"})";
        }
        return "@NebulaRW(verifiedAt=" + rw.verifiedAt() + ")";
    }

    private record AnnotationMatch(String snippet) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HotspotReport buildReport(
        List<HotspotMethod> methods, String sourceFile, int topN) {
        int annotated = (int) methods.stream()
            .filter(HotspotMethod::annotated)
            .count();
        return new HotspotReport(
            Instant.now(), sourceFile, topN,
            List.copyOf(methods), annotated, methods.size() - annotated);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Parse an async-profiler top frame into {@code (class, method,
     * descriptor)}. The async-profiler conventions are:
     * <ul>
     *   <li>Java methods: {@code "package.Class.method"} or
     *       {@code "Class.method"} for the no-package case;</li>
     *   <li>JIT-compiled Java: {@code "Class.method()"};

     *   <li>Native frames: do not contain a {@code .} separating
     *       class and method — left as {@code (class="", method=top)}.</li>
     * </ul>
     */
    static ParsedName parseMethodName(String top) {
        int paren = top.indexOf('(');
        String head = paren < 0 ? top : top.substring(0, paren);
        String descriptor = paren < 0 ? "" : top.substring(paren);

        int lastDot = head.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == head.length() - 1) {
            return new ParsedName("", head, descriptor);
        }
        String className = head.substring(0, lastDot);
        String methodName = head.substring(lastDot + 1);
        return new ParsedName(className, methodName, descriptor);
    }

    /** Result of splitting {@code "Class.method"} into components. */
    record ParsedName(String className, String methodName, String descriptor) {}
}
