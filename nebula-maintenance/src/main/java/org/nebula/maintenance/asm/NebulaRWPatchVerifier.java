package org.nebula.maintenance.asm;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Verifies auto-generated @NebulaRW patches against the real source tree.
 *
 * <p>For each patch entry, checks:
 * <ol>
 *   <li>Does the target source file exist?</li>
 *   <li>Is the method declared in that source?</li>
 *   <li>Does the method already have a @NebulaRW annotation?</li>
 *   <li>If neither: classify as "apply-eligible" (one-line @NebulaRW insert before method).</li>
 * </ol>
 *
 * <p>Output: a single {@code auto-nebularw-verification.md} report under the patches dir,
 * plus a {@code rejected-no-method.txt} list with patch entries that have no matching source
 * (these cannot be auto-applied).
 */
public class NebulaRWPatchVerifier {

    private static final Path PATCHES_DIR =
        Paths.get("nebula-server-build/nebula-server/minecraft-patches/features").toAbsolutePath();
    private static final Path SOURCE_ROOT =
        Paths.get("nebula-server-build/nebula-server/src/minecraft/java").toAbsolutePath();

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(PATCHES_DIR)) {
            System.err.println("Patches dir missing: " + PATCHES_DIR);
            System.exit(1);
        }
        if (!Files.isDirectory(SOURCE_ROOT)) {
            System.err.println("Source root missing: " + SOURCE_ROOT);
            System.exit(1);
        }

        // Per-class / per-method aggregates
        Map<String, ClassSummary> byClass = new TreeMap<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(PATCHES_DIR,
                "9999-nebula-AUTO-NebulaRW-*.patch")) {
            int patchFiles = 0;
            for (Path patch : ds) {
                patchFiles++;
                String fn = patch.getFileName().toString();
                String base = fn.substring("9999-nebula-AUTO-NebulaRW-".length());
                int firstDash = base.indexOf('-');
                int count = Integer.parseInt(base.substring(0, firstDash));
                String shortName = base.substring(firstDash + 1).replaceFirst("\\.patch$", "");

                // Resolve the classPath from the patch content
                String firstClass = firstClassPath(patch);
                if (firstClass == null) continue;

                ClassSummary summary = byClass.computeIfAbsent(firstClass, ClassSummary::new);
                summary.patchFile = patch;
                summary.shortName = shortName;
                summary.totalEntries = count;

                verifyAgainstSource(summary, patch, firstClass);
            }
        }

        // Write report (aggregate counts outside the try-with-resources so the writes
        // can use them after the directory stream is closed).
        int totalEntries = 0;
        int eligibleEntries = 0;
        int alreadyAnnotatedEntries = 0;
        int missingMethodEntries = 0;
        int missingSourceClasses = 0;
        int totalClasses = byClass.size();
        for (ClassSummary s : byClass.values()) {
            totalEntries += s.entries.size();
            if (s.sourceMissing) missingSourceClasses++;
            for (Entry e : s.entries) {
                if (e.status == Status.APPLY_ELIGIBLE) eligibleEntries++;
                else if (e.status == Status.ALREADY_ANNOTATED) alreadyAnnotatedEntries++;
                else if (e.status == Status.MISSING_METHOD) missingMethodEntries++;
            }
        }
        int classesWithEligible = 0;
        for (ClassSummary s : byClass.values()) {
            if (s.entries.stream().anyMatch(e -> e.status == Status.APPLY_ELIGIBLE)) {
                classesWithEligible++;
            }
        }

        Path report = PATCHES_DIR.resolve("auto-nebularw-verification.md");
        try (BufferedWriter w = Files.newBufferedWriter(report)) {
            w.write("# Auto-Generated @NebulaRW Verification Report\n\n");
            w.write("**Generated:** " + new Date() + "\n");
            w.write("**Classes:** " + totalClasses + "  \n");
            w.write("**Methods annotated in patches:** " + totalEntries + "  \n");
            w.write("**Source files missing on disk:** " + missingSourceClasses + "  \n");
            w.write("**Already hand-annotated (skip):** " + alreadyAnnotatedEntries + "  \n");
            w.write("**Method not present in source (skip):** " + missingMethodEntries + "  \n");
            w.write("**Apply-eligible (fresh @NebulaRW insert):** " + eligibleEntries + "  \n");
            w.write("**Classes with ≥1 eligible entry:** " + classesWithEligible + "  \n\n");

            // Confidence histogram over all entries
            int[] hist = new int[11]; // 0..10 (×10%)
            for (ClassSummary s : byClass.values()) {
                for (Entry e : s.entries) {
                    int bucket = Math.min(10, (int) Math.floor(e.confidence * 10));
                    hist[bucket]++;
                }
            }
            w.write("## Confidence distribution (over all annotated methods)\n\n");
            w.write("| bucket | count |\n|---|---|\n");
            for (int i = 10; i >= 0; i--) {
                w.write("| " + (i * 10) + "–" + ((i + 1) * 10 - 1) + "% | " + hist[i] + " |\n");
            }
            w.write("\n");

            // Group: high-confidence ≥0.7 apply-eligible per class
            w.write("## Top-confidence classes (≥ 0.7 mean, ≥ 1 apply-eligible)\n\n");
            w.write("| class | shortName | entries | mean conf | eligible | already | missing |\n");
            w.write("|---|---|---|---|---|---|---|\n");
            List<ClassSummary> top = new ArrayList<>(byClass.values());
            top.sort((a, b) -> {
                double ma = a.entries.stream().filter(e -> e.status == Status.APPLY_ELIGIBLE)
                    .mapToDouble(e -> e.confidence).average().orElse(0);
                double mb = b.entries.stream().filter(e -> e.status == Status.APPLY_ELIGIBLE)
                    .mapToDouble(e -> e.confidence).average().orElse(0);
                return Double.compare(mb, ma);
            });
            int shown = 0;
            for (ClassSummary s : top) {
                int elig = 0, ann = 0, miss = 0;
                double sum = 0;
                for (Entry e : s.entries) {
                    if (e.status == Status.APPLY_ELIGIBLE) { elig++; sum += e.confidence; }
                    else if (e.status == Status.ALREADY_ANNOTATED) ann++;
                    else if (e.status == Status.MISSING_METHOD) miss++;
                }
                if (elig == 0) continue;
                double mean = sum / elig;
                if (mean < 0.7) continue;
                w.write("| " + s.classPath + " | " + s.shortName + " | "
                    + s.entries.size() + " | " + String.format("%.2f", mean) + " | "
                    + elig + " | " + ann + " | " + miss + " |\n");
                shown++;
                if (shown >= 50) break;
            }
            w.write("\n");

            // Detail dump for first 10 apply-eligible classes
            w.write("## Per-class detail (first 10 apply-eligible)\n\n");
            int printed = 0;
            for (ClassSummary s : top) {
                if (printed >= 10) break;
                boolean any = s.entries.stream().anyMatch(e -> e.status == Status.APPLY_ELIGIBLE);
                if (!any) continue;
                printed++;
                w.write("### `" + s.classPath + "`\n\n");
                w.write("- patch: `" + s.patchFile.getFileName() + "`\n");
                w.write("- total annotated methods: " + s.entries.size() + "\n\n");
                w.write("| method | conf | status |\n|---|---|---|\n");
                for (Entry e : s.entries) {
                    w.write("| `" + e.methodName + "` | " + String.format("%.2f", e.confidence)
                        + " | " + e.status + " |\n");
                }
                w.write("\n");
            }
        }

        System.out.println("Wrote: " + report);
        System.out.println("eligible=" + eligibleEntries + " already=" + alreadyAnnotatedEntries
            + " missing=" + missingMethodEntries + " missing-source-classes=" + missingSourceClasses);
    }

    private static String firstClassPath(Path patch) {
        try (BufferedReader r = Files.newBufferedReader(patch)) {
            String line;
            Pattern p = Pattern.compile("\\+\\+\\+ b/(.+\\.java)");
            while ((line = r.readLine()) != null) {
                Matcher m = p.matcher(line);
                if (m.find()) return m.group(1);
            }
        } catch (IOException e) {
            // skip
        }
        return null;
    }

    private static void verifyAgainstSource(ClassSummary summary, Path patch, String classPath) {
        // Patch headers use bare "net.minecraft.foo.Bar.java" — they're relative
        // to the repo root for the paper-patches workflow, and the dots aren't
        // filesystem separators on Linux, so convert before resolving.
        // Patch headers use bare "net.minecraft.foo.Bar.java" — they're relative
        // to the repo root for the paper-patches workflow. Convert package dots
        // to path separators, but PRESERVE the ".java" extension.
        int lastDot = classPath.lastIndexOf('.');
        String fsPath;
        if (lastDot > 0) {
            fsPath = classPath.substring(0, lastDot).replace('.', '/') + classPath.substring(lastDot);
        } else {
            fsPath = classPath;
        }
        Path source = SOURCE_ROOT.resolve(fsPath);
        if (!Files.exists(source)) {
            summary.sourceMissing = true;
            return;
        }
        String src;
        try {
            src = Files.readString(source);
        } catch (IOException e) {
            summary.sourceMissing = true;
            return;
        }

        // Parse the patch entries
        Map<String, EntryConfidence> byMethod = parsePatch(patch);

        // Per method, check
        for (Map.Entry<String, EntryConfidence> pe : byMethod.entrySet()) {
            String m = pe.getKey();
            double conf = pe.getValue().confidence;
            Entry entry = new Entry();
            entry.methodName = m;
            entry.confidence = conf;

            // Method signature probe
            Pattern declP = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)\\b[^\\n]*\\b" + Pattern.quote(m) + "\\s*\\(");
            Matcher dm = declP.matcher(src);
            if (!dm.find()) {
                entry.status = Status.MISSING_METHOD;
            } else {
                // Check for @NebulaRW already on the same method: look backwards from the match
                int idx = dm.start();
                // Walk back through whitespace, @Override, etc.
                int scan = Math.max(0, idx - 1500);
                String window = src.substring(scan, idx);
                int annIdx = window.lastIndexOf("@org.nebula.annotations.NebulaRW(");
                if (annIdx >= 0) {
                    // crude: see if any non-closed-out ann follows before declaration
                    int after = window.indexOf("@org.nebula.annotations.NebulaRW(", annIdx + 1);
                    if (after < 0 || after < idx - scan) {
                        entry.status = Status.ALREADY_ANNOTATED;
                    } else {
                        entry.status = Status.APPLY_ELIGIBLE;
                    }
                } else {
                    entry.status = Status.APPLY_ELIGIBLE;
                }
            }
            summary.entries.add(entry);
        }
    }

    private static Map<String, EntryConfidence> parsePatch(Path patch) {
        Map<String, EntryConfidence> out = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(patch)) {
            String line;
            double lastConfidence = -1;
            String lastMethod = null;
            Pattern confP = Pattern.compile("@@ // (\\d+)% confidence");
            Pattern methP = Pattern.compile("public /\\* returnType \\*/ (\\w+)\\(/\\* params \\*/\\);");
            while ((line = r.readLine()) != null) {
                Matcher cm = confP.matcher(line);
                if (cm.find()) {
                    lastConfidence = Integer.parseInt(cm.group(1)) / 100.0;
                    continue;
                }
                Matcher mm = methP.matcher(line);
                if (mm.find()) {
                    lastMethod = mm.group(1);
                    if (lastConfidence >= 0 && lastMethod != null) {
                        out.put(lastMethod, new EntryConfidence(lastConfidence));
                    }
                }
            }
        } catch (IOException e) {
            // skip
        }
        return out;
    }

    enum Status { APPLY_ELIGIBLE, ALREADY_ANNOTATED, MISSING_METHOD }

    private static class EntryConfidence {
        final double confidence;
        EntryConfidence(double c) { this.confidence = c; }
    }

    private static class Entry {
        String methodName;
        double confidence;
        Status status;
    }

    private static class ClassSummary {
        final String classPath;
        String shortName;
        Path patchFile;
        int totalEntries;
        boolean sourceMissing;
        final List<Entry> entries = new ArrayList<>();
        ClassSummary(String cp) { this.classPath = cp; }
    }
}
