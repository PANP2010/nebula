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
 * Thin command-line front-end for {@link DropperPhaseGrader} so a shell harness can classify
 * a driven run's {@code BE-DROPPER-PHASE} snapshots with the SAME tested logic the unit tests
 * exercise — rather than re-implementing the parse+classify in bash, which would fork a second
 * grader that could silently drift from this one (the project's defining wound). Mirrors
 * {@link FurnacePhaseGraderCli} and {@link DropperSlotGapGraderCli}.
 *
 * <p>This class contains NO grading logic: it reads the log, delegates to
 * {@link DropperPhaseGrader#parse} / {@link DropperPhaseGrader#grade}, prints the report, and
 * maps the verdict to a process exit code. Exit codes mirror the other graders: {@code 0}=PASS
 * (ORDERING-ARTIFACT), {@code 3}=FAIL (RATE-DIVERGENCE), {@code 4}=INCONCLUSIVE (no samples),
 * {@code 2}=usage/IO error.
 *
 * <p>The single tunable is {@code toleranceItems}, the largest PRE-action CAS-vs-Folia gap
 * that still counts as "sync rebased exactly" (an ordering artifact). Its honest default is
 * {@code 0}: an ordering artifact requires the sync to land CAS onto Folia exactly before the
 * action runs, so any pre-action gap at all is a genuine rate divergence. The report always
 * surfaces the observed pre-action max/mean gaps and the action's self-count step range, so a
 * loosened tolerance can never hide the true classification.
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.DropperPhaseGraderCli &lt;logFile|-&gt; [toleranceItems]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 */
public final class DropperPhaseGraderCli {

    static final int DEFAULT_TOLERANCE_ITEMS = 0;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private DropperPhaseGraderCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.exit(code);
    }

    /**
     * Testable core: same behaviour as {@link #main} but returns the exit code and writes to
     * the given streams instead of calling {@link System#exit}.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 2) {
            err.println("usage: DropperPhaseGraderCli <logFile|-> [toleranceItems]");
            return EXIT_USAGE;
        }
        int toleranceItems = DEFAULT_TOLERANCE_ITEMS;
        try {
            if (args.length >= 2) {
                toleranceItems = Integer.parseInt(args[1].trim());
            }
        } catch (NumberFormatException e) {
            err.println("ERROR: toleranceItems must be an integer: " + e.getMessage());
            return EXIT_USAGE;
        }

        List<String> lines;
        try {
            lines = readLines(args[0]);
        } catch (IOException | UncheckedIOException e) {
            err.println("ERROR: cannot read log '" + args[0] + "': " + e.getMessage());
            return EXIT_USAGE;
        }

        DropperPhaseGrader.DropperPhaseReport report;
        try {
            report = DropperPhaseGrader.grade(
                DropperPhaseGrader.parse(lines), toleranceItems);
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
     * Renders the report in the same key/value style the shell harnesses use, so a driven-run
     * result file reads consistently across perf/zerodiff/divergence/timer/dropper.
     */
    static void printReport(DropperPhaseGrader.DropperPhaseReport r, PrintStream out) {
        out.println("--- Dropper/dispenser eject PHASE classification (B8 C3) ---");
        out.println("Phase snapshots:      " + r.snapshots());
        out.println("Droppers sampled:     " + r.sampledPositions());
        out.println("Rate-diverged:        " + r.rateDivergedPositions());
        out.println("Max pre self gap:     " + r.maxObservedPreGap() + " items");
        out.printf ("Mean pre self gap:    %.4f items%n", r.meanPreGap());
        out.println("Self action step:     [" + r.minSelfStep() + ".." + r.maxSelfStep() + "]");
        out.println("Tolerance:            " + r.toleranceItems() + " items");
        out.println("Classification: " + r.classification());
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: this classifies the BE-DROPPER-SLOT +1 offset. It compares the CAS");
        out.println("      self count BEFORE the DAG eject action (preSelf, just after");
        out.println("      syncFromNms rebased it to Folia) against Folia's authoritative self");
        out.println("      count. If the PRE gap is 0, the sync landed CAS onto Folia exactly");
        out.println("      and the only offset is the action's own single eject step");
        out.println("      (postSelf-preSelf) — an ORDERING ARTIFACT, so the shadow tracks Folia");
        out.println("      at rate 1:1 and a write-back sampled PRE-action could be honest. A");
        out.println("      nonzero PRE gap is a genuine RATE DIVERGENCE: leave the dropper to");
        out.println("      Folia (the double-ejector trap). The self action-step range lets you");
        out.println("      confirm the action stepped by the expected -1 (pulsing) or 0 (rest).");
    }

    private static String verdictLine(DropperPhaseGrader.DropperPhaseReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS (ORDERING-ARTIFACT) — worst pre-action gap %d <= %d items over %d "
                    + "sampled dropper(s); the +1 is the action's own eject step, not a rate gap",
                r.maxObservedPreGap(), r.toleranceItems(), r.sampledPositions());
            case FAIL -> String.format(
                "FAIL (RATE-DIVERGENCE) — worst pre-action gap %d > %d items (%d/%d droppers "
                    + "drifted from Folia BEFORE the action); leave the dropper to Folia",
                r.maxObservedPreGap(), r.toleranceItems(), r.rateDivergedPositions(), r.sampledPositions());
            case INCONCLUSIVE -> "INCONCLUSIVE — no droppers sampled "
                + "(no BE-DROPPER-PHASE lines: no dropper/dispenser was pulsing when the probe "
                + "fired, or the plugin emit is absent)";
        };
    }
}
