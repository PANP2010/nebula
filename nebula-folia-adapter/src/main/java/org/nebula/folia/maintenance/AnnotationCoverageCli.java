package org.nebula.folia.maintenance;

import org.nebula.annotations.NebulaRW;
import org.nebula.folia.NmsBlockEntityStateBridge;
import org.nebula.folia.NmsBlockStateBridge;
import org.nebula.folia.NmsEntityStateBridge;
import org.nebula.folia.NmsFluidStateBridge;
import org.nebula.maintenance.BridgeAnnotationScanner;
import org.nebula.maintenance.BridgeAnnotationScanner.ScanTarget;
import org.nebula.maintenance.BridgeAnnotationScanner.SubsystemCoverage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless CLI for the Annotation Regression CI (P1.5.2).
 *
 * <p>Mirrors {@code NebulaPlugin.buildCoverageDashboard()}: scans the runtime
 * bridge classes reflectively for {@link NebulaRW}-annotated public instance
 * methods and emits a single-line JSON document on stdout. The shell wrappers
 * ({@code scripts/print-coverage.sh} and
 * {@code scripts/annotation-coverage-check.sh}) consume that JSON verbatim.
 *
 * <p><b>Why not just call {@code /nebula coverage} in a live server?</b>
 * Because the regression gate must run before any deploy — it has to be
 * usable in CI without spinning up a Paper/Folia server. Reflection over
 * the compiled classes is the same evidence the runtime dashboard uses.
 *
 * <p>Usage: {@code java -cp <classpath> org.nebula.folia.maintenance.AnnotationCoverageCli 1.21.4}
 *
 * <p>The JSON shape is the schema both {@code print-coverage.sh} and
 * {@code annotation-coverage-check.sh} depend on:
 * <pre>{@code
 * {
 *   "schema_version": 1,
 *   "minecraft_version": "1.21.4",
 *   "generated_at": "2026-07-12T09:21:00Z",
 *   "total_bridge_methods": 247,
 *   "annotated_methods": 189,
 *   "coverage_ratio": 0.765,
 *   "subsystems": [
 *     {"name":"redstone-bridge","annotated":N,"total":N,"coverage_ratio":0.x}
 *   ],
 *   "methods": [
 *     {"class":"org.nebula.folia.NmsBlockStateBridge","method":"syncToNms","annotated":true,"verified_at":"1.21.4"}
 *   ]
 * }
 * }</pre>
 */
public final class AnnotationCoverageCli {

    /** Bump when the JSON layout changes; consumers gate on this. */
    public static final int SCHEMA_VERSION = 1;

    private AnnotationCoverageCli() {}

    public static void main(String[] args) {
        String mcVersion = args.length > 0 ? args[0] : "1.21.4";
        if (!mcVersion.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            System.err.println("[annotation-coverage] invalid minecraft_version: " + mcVersion);
            System.exit(2);
        }

        // Same subsystem → root-bridge mapping as NebulaPlugin.buildCoverageDashboard().
        // Add a new entry here AND to that method when a new bridge class ships.
        List<ScanTarget> targets = List.of(
            ScanTarget.of("redstone-bridge",     NmsBlockStateBridge.class),
            ScanTarget.of("block-entity-bridge", NmsBlockEntityStateBridge.class),
            ScanTarget.of("fluid-bridge",        NmsFluidStateBridge.class),
            ScanTarget.of("entity-bridge",       NmsEntityStateBridge.class));

        List<SubsystemCoverage> rows = BridgeAnnotationScanner.getSubsystemCoverage(targets);

        // Walk each target once more to collect the per-method list. The scanner
        // only returns aggregates, so we re-reflect here to record the actual
        // (class, method, annotated, verifiedAt) tuples the regression diff
        // script keys on.
        Map<String, List<MethodRow>> methodsByClass = new TreeMap<>();
        for (ScanTarget t : targets) {
            for (Class<?> cls : List.of(t.root())) {
                methodsByClass.computeIfAbsent(cls.getName(), k -> new ArrayList<>());
                for (Method m : cls.getDeclaredMethods()) {
                    int mods = m.getModifiers();
                    if (!(Modifier.isPublic(mods) && !Modifier.isStatic(mods))) continue;
                    if (m.isSynthetic() || m.isBridge()) continue;
                    NebulaRW rw = m.getAnnotation(NebulaRW.class);
                    String verified = rw == null ? null : emptyToNull(rw.verifiedAt());
                    methodsByClass.get(cls.getName()).add(
                        new MethodRow(simpleName(cls), m.getName(), rw != null, verified));
                }
            }
        }

        int totalBridge = rows.stream().mapToInt(SubsystemCoverage::totalBridgeMethods).sum();
        int annotated   = rows.stream().mapToInt(SubsystemCoverage::annotatedMethods).sum();
        double ratio    = totalBridge == 0 ? 1.0 : (double) annotated / totalBridge;

        StringBuilder json = new StringBuilder(4096);
        json.append('{').append('\n');
        appendField(json, "schema_version",     String.valueOf(SCHEMA_VERSION), true);
        appendField(json, "minecraft_version",  jsonString(mcVersion),            false);
        appendField(json, "generated_at",       jsonString(Instant.now().toString()), false);
        appendField(json, "total_bridge_methods", String.valueOf(totalBridge),    false);
        appendField(json, "annotated_methods",    String.valueOf(annotated),      false);
        appendField(json, "coverage_ratio",       formatRatio(ratio),             false);

        // Subsystems array (alphabetical for stable diffs).
        Map<String, SubsystemCoverage> ordered = new LinkedHashMap<>();
        rows.stream()
            .sorted((a, b) -> a.subsystem().compareTo(b.subsystem()))
            .forEach(r -> ordered.put(r.subsystem(), r));
        appendFieldRaw(json, "subsystems", false);
        json.append("[\n");
        int idx = 0;
        for (SubsystemCoverage r : ordered.values()) {
            json.append("  {");
            appendField(json, "name",            jsonString(r.subsystem()), true);
            appendField(json, "annotated",       String.valueOf(r.annotatedMethods()), false);
            appendField(json, "total",           String.valueOf(r.totalBridgeMethods()), false);
            appendField(json, "coverage_ratio",  formatRatio(r.coverageRatio()), false);
            json.append('}');
            if (++idx < ordered.size()) json.append(',');
            json.append('\n');
        }
        json.append("]\n");

        // Methods array — flat list keyed by simple class name. Stable order via TreeMap above.
        appendFieldRaw(json, "methods", false);
        json.append("[\n");
        int totalMethods = methodsByClass.values().stream().mapToInt(List::size).sum();
        int written = 0;
        for (Map.Entry<String, List<MethodRow>> e : methodsByClass.entrySet()) {
            for (MethodRow mr : e.getValue()) {
                json.append("  {");
                appendField(json, "class",        jsonString(mr.className),  true);
                appendField(json, "method",       jsonString(mr.methodName), false);
                appendField(json, "annotated",    String.valueOf(mr.annotated), false);
                appendField(json, "verified_at",  jsonString(mr.verifiedAt), false);
                json.append('}');
                if (++written < totalMethods) json.append(',');
                json.append('\n');
            }
        }
        json.append("]\n");

        json.append('}').append('\n');
        System.out.print(json);
    }

    private static void appendField(StringBuilder sb, String key, String value, boolean firstInObject) {
        if (!firstInObject) sb.append(',');
        sb.append('\n').append("  ").append(jsonString(key)).append(": ").append(value);
    }

    private static void appendFieldRaw(StringBuilder sb, String key, boolean firstInObject) {
        if (!firstInObject) sb.append(',');
        sb.append('\n').append("  ").append(jsonString(key)).append(": ");
    }

    /** RFC-8259 string encoder. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String formatRatio(double r) {
        // Four decimal places is plenty; the JSON reader keeps the full double.
        return String.format(java.util.Locale.ROOT, "%.4f", r);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String simpleName(Class<?> cls) {
        String n = cls.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }

    /** Per-method row carried into the JSON methods array. */
    private record MethodRow(String className, String methodName, boolean annotated, String verifiedAt) {}
}
