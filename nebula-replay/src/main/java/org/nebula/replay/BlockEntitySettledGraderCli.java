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
 * Thin command-line front-end for {@link BlockEntitySettledGrader} so a shell harness
 * can grade a driven run's {@code BE-SETTLED} snapshots with the SAME tested logic the
 * unit tests exercise — rather than re-implementing the parse+grade in bash, which
 * would fork a second grader that could silently drift from this one (the project's
 * defining wound). Mirrors {@link SettledDivergenceGraderCli} on the redstone path.
 *
 * <p>This class contains NO grading logic: it only reads the log, delegates to
 * {@link BlockEntitySettledGrader#parse} / {@link BlockEntitySettledGrader#grade},
 * prints the report, and maps the verdict to a process exit code. The exit codes
 * mirror the other graders: {@code 0}=PASS, {@code 3}=FAIL, {@code 4}=INCONCLUSIVE,
 * {@code 2}=usage/IO error.
 *
 * <p>There is NO converge window: every {@code BE-SETTLED} sample is taken <em>at</em>
 * quiescence, so there is no cold-start transient to skip. The single tunable is
 * {@code maxDivergenceRate}, whose honest default is {@code 0.0} — at genuine
 * quiescence a correct observe-only shadow must match Folia's authoritative inventory
 * count exactly, because it has had every intervening tick to catch up.
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.BlockEntitySettledGraderCli &lt;logFile|-&gt; [maxDivergenceRate]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 */
public final class BlockEntitySettledGraderCli {

    static final double DEFAULT_MAX_DIVERGENCE_RATE = 0.0;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private BlockEntitySettledGraderCli() {
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
            err.println("usage: BlockEntitySettledGraderCli <logFile|-> [maxDivergenceRate]");
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

        BlockEntitySettledGrader.BlockEntitySettledReport report;
        try {
            report = BlockEntitySettledGrader.grade(
                BlockEntitySettledGrader.parse(lines), maxDivergenceRate);
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
    static void printReport(BlockEntitySettledGrader.BlockEntitySettledReport r, PrintStream out) {
        out.println("--- Folia-vs-Nebula BLOCK-ENTITY SETTLED-state divergence grade (B8 C3) ---");
        out.println("Settled snapshots:   " + r.snapshots());
        out.println("Positions sampled:   " + r.sampledPositions());
        out.println("Diverged positions:  " + r.divergedPositions());
        out.printf ("Divergence rate:     %.4f%n", r.divergenceRate());
        out.printf ("Max divergence rate: %.4f%n", r.maxDivergenceRate());
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: every sample is taken AT quiescence (the hopper has stopped");
        out.println("      transferring), where the observe-only shadow has had every");
        out.println("      intervening tick to catch up — so there is NO cooldown-lag excuse.");
        out.println("      Any nebula!=folia inventory count here is a genuine Folia-vs-Nebula");
        out.println("      divergence, not the 8-tick cooldown lag the BE-CAS-DIAG stream shows.");
    }

    private static String verdictLine(BlockEntitySettledGrader.BlockEntitySettledReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS — settled divergence rate %.4f <= %.4f over %d sampled positions",
                r.divergenceRate(), r.maxDivergenceRate(), r.sampledPositions());
            case FAIL -> String.format(
                "FAIL — settled divergence rate %.4f > %.4f (%d/%d positions diverge at quiescence)",
                r.divergenceRate(), r.maxDivergenceRate(), r.divergedPositions(), r.sampledPositions());
            case INCONCLUSIVE -> "INCONCLUSIVE — no settled block entities sampled "
                + "(no BE-SETTLED lines: the driven hoppers never reached quiescence, "
                + "or the plugin emit is absent)";
        };
    }
}
