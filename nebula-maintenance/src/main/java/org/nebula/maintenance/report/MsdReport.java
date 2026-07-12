package org.nebula.maintenance.report;

import org.nebula.maintenance.asm.DiffLevel;
import org.nebula.maintenance.asm.MethodDelta;
import org.nebula.maintenance.asm.MethodSignatureDeltaDetector.PerClassResult;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a Method Signature Delta (MSD) report from a list of
 * {@link PerClassResult} and {@link PatchCandidate}s
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>The report has two parts:
 *
 * <ol>
 *   <li><b>Summary</b> — counts per {@link DiffLevel}, automation coverage
 *       ratio, file counts.</li>
 *   <li><b>Per-class detail</b> — one block per class, with the
 *       {@code +} (NEW) / {@code ~} (CHANGED) / {@code -} (REMOVED)
 *       markers and the suggested {@code @NebulaRW} patch for L0 entries.</li>
 * </ol>
 *
 * <p>The intended output format (and the source of truth for the human-review
 * workflow):
 *
 * <pre>
 * === MSD Report ===
 * File: bridge-classes.txt
 * Class: net/minecraft/world/entity/Entity
 *   + public void customName(Ljava/lang/String;)V [L0 - NEW METHOD]
 *     Suggestion: @NebulaRW(read={"entity.customName"}, ...)
 *   ~ public int getHealth()I [L1 - DESCRIPTOR CHANGED: was ()I]
 *   - private void tick()V [L2 - REMOVED]
 *
 * Summary:
 *   L0 NEW               : 12
 *   L1 DESCRIPTOR_CHANGED:  4
 *   L2 VISIBILITY_CHANGED: 1
 *   L2 REMOVED           :  3
 *   Auto-migrate rate    : 16/20 = 80.0%
 * </pre>
 *
 * <p>The {@link #render()} method returns the report as a single string.
 * Convenience methods {@link #writeToConsole()} and {@link #writeToFile(Path)}
 * route it to stdout and disk respectively. The file form is what CI
 * consumes.
 */
public final class MsdReport {

    private final List<PerClassResult> perClass;
    private final List<PatchCandidate> patches;
    private final String sourceFileLabel;

    public MsdReport(String sourceFileLabel,
                     List<PerClassResult> perClass,
                     List<PatchCandidate> patches) {
        this.sourceFileLabel = sourceFileLabel == null ? "<unknown>" : sourceFileLabel;
        this.perClass = List.copyOf(perClass);
        this.patches = List.copyOf(patches);
    }

    /** Render the full report as a single String. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MSD Report ===\n");
        sb.append("File: ").append(sourceFileLabel).append('\n');

        Map<DiffLevel, Integer> counts = new LinkedHashMap<>();
        for (DiffLevel l : DiffLevel.values()) counts.put(l, 0);
        int total = 0;

        // Bucket patches by (owner, method, descriptor) for lookup.
        Map<String, PatchCandidate> patchIndex = new LinkedHashMap<>();
        for (PatchCandidate p : patches) {
            patchIndex.put(p.targetClass() + "#" + p.targetMethod() + " " + p.targetDescriptor(), p);
        }

        for (PerClassResult cls : perClass) {
            sb.append("Class: ").append(cls.ownerClass()).append('\n');
            // Sort deltas: NEW first, then CHANGED, then REMOVED.
            List<MethodDelta> sorted = new ArrayList<>(cls.deltas());
            sorted.sort((a, b) -> Integer.compare(a.level().numericLevel(), b.level().numericLevel()));
            for (MethodDelta d : sorted) {
                counts.merge(d.level(), 1, Integer::sum);
                total++;
                sb.append("  ").append(marker(d.level())).append(' ')
                  .append(formatSignature(d)).append(" [").append(d.levelTag()).append("]");
                if (!d.note().isEmpty()) {
                    sb.append("  ").append(d.note());
                }
                sb.append('\n');

                PatchCandidate patch = patchIndex.get(
                    d.ownerClass() + "#" + d.methodName() + " " + d.newJvmDescriptor());
                if (patch != null) {
                    sb.append("    Suggestion:\n");
                    for (String line : patch.annotation().split("\n")) {
                        sb.append("      ").append(line).append('\n');
                    }
                    sb.append("    Reasoning: ").append(patch.reasoning()).append('\n');
                }
            }
        }

        sb.append("\nSummary:\n");
        for (Map.Entry<DiffLevel, Integer> e : counts.entrySet()) {
            sb.append(String.format("  %-22s: %3d%n", e.getKey().name(), e.getValue()));
        }
        if (total == 0) {
            sb.append("  No changes detected.\n");
            sb.append("  Auto-migrate rate    : 100.0% (vacuous)\n");
        } else {
            long auto = counts.getOrDefault(DiffLevel.NEW, 0)
                + counts.getOrDefault(DiffLevel.DESCRIPTOR_CHANGED, 0);
            double rate = (double) auto / total * 100.0;
            sb.append(String.format("  Auto-migrate rate    : %d/%d = %.1f%%%n",
                auto, total, rate));
        }
        sb.append(String.format("  Classes scanned      : %d%n", perClass.size()));
        sb.append(String.format("  Patches drafted      : %d%n", patches.size()));
        return sb.toString();
    }

    /** Print the report to stdout. */
    public void writeToConsole() {
        System.out.print(render());
    }

    /** Write the report to a UTF-8 text file. Returns the bytes written. */
    public long writeToFile(Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        String body = render();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Files.write(destination, bytes);
        return bytes.length;
    }

    private static String marker(DiffLevel level) {
        return switch (level) {
            case NEW -> "+";
            case DESCRIPTOR_CHANGED -> "~";
            case VISIBILITY_CHANGED -> "*";
            case REMOVED -> "-";
        };
    }

    private static String formatSignature(MethodDelta d) {
        if (d.level() == DiffLevel.REMOVED) {
            var old = d.oldDescOrNull();
            return old == null
                ? d.methodName() + "()?"
                : old.visibilityLabel() + " " + old.methodName() + old.descriptor();
        }
        var next = d.newDescOrNull();
        if (next == null) return d.methodName() + "()?";

        String prefix = next.visibilityLabel();
        if (next.isStatic()) prefix += " static";
        if (next.isFinal()) prefix += " final";
        return prefix + " " + next.methodName() + next.descriptor();
    }
}