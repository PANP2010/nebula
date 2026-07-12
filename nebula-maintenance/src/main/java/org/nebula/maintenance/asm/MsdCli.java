package org.nebula.maintenance.asm;

import org.nebula.maintenance.asm.MethodSignatureDeltaDetector.PerClassResult;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate;
import org.nebula.maintenance.report.MsdReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point for the Method Signature Delta detector
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>Usage:
 * <pre>{@code
 *   java -cp ... org.nebula.maintenance.asm.MsdCli \
 *        --old <old-classes-dir> \
 *        --new <new-classes-dir> \
 *        [--report <report.txt>] \
 *        [--rename oldRef=newRef]... \
 *        [--include-accessors]
 * }</pre>
 *
 * <p>The CLI walks both directories recursively, extracts every
 * {@code .class} file's methods via {@link BytecodeMethodExtractor}, runs
 * {@link MethodSignatureDeltaDetector} on the bucketed per-class index, and
 * emits an {@link MsdReport} either to stdout or to the {@code --report} path.
 *
 * <p>It is intentionally minimal — the heavy lifting lives in the detector
 * and report classes. The CLI exists to make the toolchain usable from CI
 * scripts and from the human-review workflow that runs on a developer's
 * workstation.
 */
public final class MsdCli {

    private static final String USAGE = """
        Usage: MsdCli --old <old-classes-dir> --new <new-classes-dir>
                     [--report <report.txt>]
                     [--rename oldKey=newKey]...
                     [--include-accessors]
        """;

    public static void main(String[] args) throws IOException {
        Path oldDir = null;
        Path newDir = null;
        Path reportPath = null;
        List<String> renames = new ArrayList<>();
        boolean includeAccessors = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--old" -> oldDir = Path.of(args[++i]);
                case "--new" -> newDir = Path.of(args[++i]);
                case "--report" -> reportPath = Path.of(args[++i]);
                case "--rename" -> renames.add(args[++i]);
                case "--include-accessors" -> includeAccessors = true;
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    System.exit(0);
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                }
            }
        }

        if (oldDir == null || newDir == null) {
            System.err.println(USAGE);
            System.exit(2);
        }
        if (!Files.isDirectory(oldDir)) {
            System.err.println("--old is not a directory: " + oldDir);
            System.exit(2);
        }
        if (!Files.isDirectory(newDir)) {
            System.err.println("--new is not a directory: " + newDir);
            System.exit(2);
        }

        Map<String, List<BytecodeMethodDescriptor>> oldByClass = indexByClass(oldDir);
        Map<String, List<BytecodeMethodDescriptor>> newByClass = indexByClass(newDir);

        MethodSignatureDeltaDetector detector = new MethodSignatureDeltaDetector();
        for (String rename : renames) {
            int eq = rename.indexOf('=');
            if (eq < 0) {
                System.err.println("--rename expects oldKey=newKey form, got: " + rename);
                System.exit(2);
            }
            detector.setRenameHint(rename.substring(0, eq), rename.substring(eq + 1));
        }

        List<PerClassResult> perClass = detector.detectByClass(oldByClass, newByClass);

        NebulaRWAnnotationPatcher patcher = new NebulaRWAnnotationPatcher();
        patcher.setSkipAccessors(!includeAccessors);

        List<MethodDelta> allDeltas = new ArrayList<>();
        for (PerClassResult r : perClass) allDeltas.addAll(r.deltas());
        List<PatchCandidate> patches = patcher.draftAll(allDeltas);

        String label = oldDir.getFileName() + " vs " + newDir.getFileName();
        MsdReport report = new MsdReport(label, perClass, patches);

        if (reportPath != null) {
            report.writeToFile(reportPath);
            System.out.println("MSD report written: " + reportPath.toAbsolutePath());
        } else {
            report.writeToConsole();
        }
    }

    private static Map<String, List<BytecodeMethodDescriptor>> indexByClass(Path root)
        throws IOException {
        Map<String, List<BytecodeMethodDescriptor>> byClass = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".class"))::iterator) {
                for (BytecodeMethodDescriptor d : BytecodeMethodExtractor.extract(p)) {
                    byClass.computeIfAbsent(d.ownerClass(), k -> new ArrayList<>()).add(d);
                }
            }
        }
        return byClass;
    }
}