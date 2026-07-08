package org.nebula.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Thin command-line front-end for {@link FoliaDivergenceGrader} so a shell harness
 * (e.g. {@code scripts/divergence-grade.sh}) can grade a driven live-load run's
 * {@code CASCADE-DIAG} lines with the SAME tested logic the unit tests exercise —
 * rather than re-implementing the parse+grade in bash, which would fork a second
 * grader that could silently drift from this one (the project's defining wound).
 *
 * <p>This class contains NO grading logic: it only reads the log, delegates to
 * {@link FoliaDivergenceGrader#parse} / {@link FoliaDivergenceGrader#grade}, prints
 * the {@link FoliaDivergenceGrader.DivergenceReport}, and maps the verdict to a
 * process exit code. The exit codes mirror {@code scripts/perf-harness.sh} and
 * {@code scripts/zerodiff-harness.sh}: {@code 0}=PASS, {@code 3}=FAIL,
 * {@code 4}=INCONCLUSIVE, {@code 2}=usage/IO error.
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.FoliaDivergenceGraderCli &lt;logFile|-&gt; [convergeWindow] [maxDirtyRate]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 * {@code convergeWindow} defaults to 8 (the cold-CAS opening transient, matching the
 * zerodiff-harness CONVERGE_MAX prefix intent); {@code maxDirtyRate} defaults to
 * {@code 0.05}. See the grader's javadoc for why a low residual dirty rate is
 * necessary-but-not-sufficient for Folia-vs-Nebula correctness (observe-only
 * boundary lag).
 */
public final class FoliaDivergenceGraderCli {

    static final int DEFAULT_CONVERGE_WINDOW = 8;
    static final double DEFAULT_MAX_DIRTY_RATE = 0.05;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private FoliaDivergenceGraderCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.exit(code);
    }

    /**
     * Testable core: same behaviour as {@link #main} but returns the exit code and
     * writes to the given streams instead of calling {@link System#exit}.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 3) {
            err.println("usage: FoliaDivergenceGraderCli <logFile|-> [convergeWindow] [maxDirtyRate]");
            return EXIT_USAGE;
        }
        int convergeWindow = DEFAULT_CONVERGE_WINDOW;
        double maxDirtyRate = DEFAULT_MAX_DIRTY_RATE;
        try {
            if (args.length >= 2) {
                convergeWindow = Integer.parseInt(args[1].trim());
            }
            if (args.length >= 3) {
                maxDirtyRate = Double.parseDouble(args[2].trim());
            }
        } catch (NumberFormatException e) {
            err.println("ERROR: convergeWindow must be an int and maxDirtyRate a double: " + e.getMessage());
            return EXIT_USAGE;
        }

        List<String> lines;
        try {
            lines = readLines(args[0]);
        } catch (IOException | UncheckedIOException e) {
            err.println("ERROR: cannot read log '" + args[0] + "': " + e.getMessage());
            return EXIT_USAGE;
        }

        FoliaDivergenceGrader.DivergenceReport report;
        try {
            report = FoliaDivergenceGrader.grade(
                FoliaDivergenceGrader.parse(lines), convergeWindow, maxDirtyRate);
        } catch (IllegalArgumentException e) {
            err.println("ERROR: " + e.getMessage());
            return EXIT_USAGE;
        }

        printReport(report, out);
        return exitCodeFor(report.verdict());
    }

    /** Reads all lines from a file path, or from stdin when {@code source} is "-". */
    private static List<String> readLines(String source) throws IOException {
        if ("-".equals(source)) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                return r.lines().toList();
            }
        }
        return Files.readAllLines(Path.of(source), StandardCharsets.UTF_8);
    }

    static int exitCodeFor(FoliaDivergenceGrader.Verdict verdict) {
        return switch (verdict) {
            case PASS -> EXIT_PASS;
            case FAIL -> EXIT_FAIL;
            case INCONCLUSIVE -> EXIT_INCONCLUSIVE;
        };
    }

    /**
     * Renders the report in the same key/value style the shell harnesses use, so a
     * driven-run result file reads consistently across perf/zerodiff/divergence.
     */
    static void printReport(FoliaDivergenceGrader.DivergenceReport r, PrintStream out) {
        out.println("--- Folia-vs-Nebula divergence grade (DG3 correctness) ---");
        out.println("Invocations parsed:  " + r.totalInvocations());
        out.println("Converge window:     " + r.convergeWindow() + " (skipped as cold-CAS transient)");
        out.println("Invocations graded:  " + r.gradedInvocations());
        out.println("Seed samples graded: " + r.gradedSamples());
        out.println("Dirty samples:       " + r.dirtySamples());
        out.printf ("Residual dirty rate: %.4f%n", r.dirtyRate());
        out.printf ("Max dirty rate:      %.4f%n", r.maxDirtyRate());
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: a low residual dirty rate is NECESSARY-BUT-NOT-SUFFICIENT for");
        out.println("      Folia-vs-Nebula agreement. Nebula is observe-only on the redstone");
        out.println("      path, so a seed sampled AT a toggle boundary reads dirty from a");
        out.println("      one-cycle observe lag, not a bug. Cleanly separating boundary lag");
        out.println("      from a real divergence needs settled-state sampling (a later slice).");
    }

    private static String verdictLine(FoliaDivergenceGrader.DivergenceReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS — residual dirty rate %.4f <= %.4f over %d graded samples",
                r.dirtyRate(), r.maxDirtyRate(), r.gradedSamples());
            case FAIL -> String.format(
                "FAIL — residual dirty rate %.4f > %.4f (sustained post-transient divergence over %d samples)",
                r.dirtyRate(), r.maxDirtyRate(), r.gradedSamples());
            case INCONCLUSIVE -> "INCONCLUSIVE — no graded samples remain after the converge window "
                + "(no CASCADE-DIAG lines, or convergeWindow >= invocation count)";
        };
    }
}
