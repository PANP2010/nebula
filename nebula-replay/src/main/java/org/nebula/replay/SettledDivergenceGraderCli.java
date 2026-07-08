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
 * Thin command-line front-end for {@link SettledDivergenceGrader} so a shell harness
 * (e.g. {@code scripts/divergence-grade.sh}) can grade a driven run's
 * {@code SETTLED-DIAG} snapshots with the SAME tested logic the unit tests exercise —
 * rather than re-implementing the parse+grade in bash, which would fork a second
 * grader that could silently drift from this one (the project's defining wound).
 *
 * <p>This class contains NO grading logic: it only reads the log, delegates to
 * {@link SettledDivergenceGrader#parse} / {@link SettledDivergenceGrader#grade},
 * prints the {@link SettledDivergenceGrader.SettledReport}, and maps the verdict to a
 * process exit code. The exit codes mirror {@link FoliaDivergenceGraderCli},
 * {@code scripts/perf-harness.sh}, and {@code scripts/zerodiff-harness.sh}:
 * {@code 0}=PASS, {@code 3}=FAIL, {@code 4}=INCONCLUSIVE, {@code 2}=usage/IO error.
 *
 * <p>Unlike {@link FoliaDivergenceGraderCli} there is NO converge window: every
 * {@code SETTLED-DIAG} sample is taken <em>at</em> quiescence, so there is no
 * cold-start transient to skip. The single tunable is {@code maxDivergenceRate},
 * whose honest default is {@code 0.0} — at genuine quiescence a correct observe-only
 * shadow must match Folia's authoritative power exactly, because it has had every
 * intervening tick to catch up (see the grader's javadoc for why this is the
 * load-bearing signal the residual dirty rate is not).
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.SettledDivergenceGraderCli &lt;logFile|-&gt; [maxDivergenceRate]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 */
public final class SettledDivergenceGraderCli {

    static final double DEFAULT_MAX_DIVERGENCE_RATE = 0.0;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private SettledDivergenceGraderCli() {
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
        if (args.length < 1 || args.length > 2) {
            err.println("usage: SettledDivergenceGraderCli <logFile|-> [maxDivergenceRate]");
            return EXIT_USAGE;
        }
        double maxDivergenceRate = DEFAULT_MAX_DIVERGENCE_RATE;
        try {
            if (args.length >= 2) {
                maxDivergenceRate = Double.parseDouble(args[1].trim());
            }
        } catch (NumberFormatException e) {
            err.println("ERROR: maxDivergenceRate must be a double: " + e.getMessage());
            return EXIT_USAGE;
        }

        List<String> lines;
        try {
            lines = readLines(args[0]);
        } catch (IOException | UncheckedIOException e) {
            err.println("ERROR: cannot read log '" + args[0] + "': " + e.getMessage());
            return EXIT_USAGE;
        }

        SettledDivergenceGrader.SettledReport report;
        try {
            report = SettledDivergenceGrader.grade(
                SettledDivergenceGrader.parse(lines), maxDivergenceRate);
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
    static void printReport(SettledDivergenceGrader.SettledReport r, PrintStream out) {
        out.println("--- Folia-vs-Nebula SETTLED-state divergence grade (DG3 correctness) ---");
        out.println("Settled snapshots:   " + r.snapshots());
        out.println("Positions sampled:   " + r.sampledPositions());
        out.println("Diverged positions:  " + r.divergedPositions());
        out.printf ("Divergence rate:     %.4f%n", r.divergenceRate());
        out.printf ("Max divergence rate: %.4f%n", r.maxDivergenceRate());
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: every sample is taken AT quiescence (sustained microsteps=0),");
        out.println("      where the observe-only shadow has had every intervening tick to");
        out.println("      catch up — so there is NO boundary-lag excuse. Any nebula!=folia");
        out.println("      here is a genuine Folia-vs-Nebula divergence, not observe lag.");
        out.println("      This is the load-bearing signal the residual dirty rate is not.");
    }

    private static String verdictLine(SettledDivergenceGrader.SettledReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS — settled divergence rate %.4f <= %.4f over %d sampled positions",
                r.divergenceRate(), r.maxDivergenceRate(), r.sampledPositions());
            case FAIL -> String.format(
                "FAIL — settled divergence rate %.4f > %.4f (%d/%d positions diverge at quiescence)",
                r.divergenceRate(), r.maxDivergenceRate(), r.divergedPositions(), r.sampledPositions());
            case INCONCLUSIVE -> "INCONCLUSIVE — no settled positions sampled "
                + "(no SETTLED-DIAG lines: the driven circuit never reached quiescence, "
                + "or the plugin emit is absent)";
        };
    }
}
