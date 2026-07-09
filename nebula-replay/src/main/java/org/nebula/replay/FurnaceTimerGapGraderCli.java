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
 * Thin command-line front-end for {@link FurnaceTimerGapGrader} so a shell harness can
 * grade a driven run's {@code BE-FURNACE-TIMER} snapshots with the SAME tested logic the
 * unit tests exercise — rather than re-implementing the parse+grade in bash, which would
 * fork a second grader that could silently drift from this one (the project's defining
 * wound). Mirrors {@link BlockEntitySettledGraderCli} and {@link SettledDivergenceGraderCli}.
 *
 * <p>This class contains NO grading logic: it only reads the log, delegates to
 * {@link FurnaceTimerGapGrader#parse} / {@link FurnaceTimerGapGrader#grade}, prints the
 * report, and maps the verdict to a process exit code. The exit codes mirror the other
 * graders: {@code 0}=PASS, {@code 3}=FAIL, {@code 4}=INCONCLUSIVE, {@code 2}=usage/IO error.
 *
 * <p>The single tunable is {@code toleranceTicks}, the largest per-furnace timer gap that
 * still PASSes. Its honest default is {@code 0}: for the observe-only shadow to be a safe
 * write-back mirror it must track Folia's authoritative timers exactly, the same
 * precondition the entity Y-drift met before its vertical write-back was armed. A nonzero
 * tolerance is for characterizing HOW far off the shadow is, not for papering over a real
 * gap — the printed report always surfaces the observed max/mean gaps so a loosened
 * tolerance can never hide the true divergence.
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.FurnaceTimerGapGraderCli &lt;logFile|-&gt; [toleranceTicks]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 */
public final class FurnaceTimerGapGraderCli {

    static final int DEFAULT_TOLERANCE_TICKS = 0;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private FurnaceTimerGapGraderCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.exit(code);
    }

    /**
     * Testable core: same behaviour as {@link #main} but returns the exit code and writes
     * to the given streams instead of calling {@link System#exit}.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 2) {
            err.println("usage: FurnaceTimerGapGraderCli <logFile|-> [toleranceTicks]");
            return EXIT_USAGE;
        }
        int toleranceTicks = DEFAULT_TOLERANCE_TICKS;
        try {
            if (args.length >= 2) {
                toleranceTicks = Integer.parseInt(args[1].trim());
            }
        } catch (NumberFormatException e) {
            err.println("ERROR: toleranceTicks must be an integer: " + e.getMessage());
            return EXIT_USAGE;
        }

        List<String> lines;
        try {
            lines = readLines(args[0]);
        } catch (IOException | UncheckedIOException e) {
            err.println("ERROR: cannot read log '" + args[0] + "': " + e.getMessage());
            return EXIT_USAGE;
        }

        FurnaceTimerGapGrader.FurnaceTimerReport report;
        try {
            report = FurnaceTimerGapGrader.grade(
                FurnaceTimerGapGrader.parse(lines), toleranceTicks);
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
    static void printReport(FurnaceTimerGapGrader.FurnaceTimerReport r, PrintStream out) {
        out.println("--- Folia-vs-Nebula FURNACE-TIMER gap grade (B8 C3) ---");
        out.println("Timer snapshots:      " + r.snapshots());
        out.println("Furnaces sampled:     " + r.sampledPositions());
        out.println("Diverged furnaces:    " + r.divergedPositions());
        out.println("Max fuel_time gap:    " + r.maxFuelGap() + " ticks");
        out.println("Max cook_progress gap:" + r.maxCookGap() + " ticks");
        out.printf ("Mean fuel_time gap:   %.4f ticks%n", r.meanFuelGap());
        out.printf ("Mean cook gap:        %.4f ticks%n", r.meanCookGap());
        out.println("Tolerance:            " + r.toleranceTicks() + " ticks");
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: furnace timers CYCLE (cook_progress climbs 0..COOK_TOTAL and");
        out.println("      resets; fuel_time counts a fuel item down), so this grades the");
        out.println("      GAP magnitude between the observe-only shadow's timers and Folia's,");
        out.println("      NOT settled equality. A gap of 0 is the safe-mirror precondition a");
        out.println("      furnace-timer write-back must meet BEFORE it is armed (the entity");
        out.println("      Y-drift met the same bar). A nonzero gap quantifies how far off the");
        out.println("      shadow is — it is a MEASUREMENT, not proof furnace physics is correct.");
    }

    private static String verdictLine(FurnaceTimerGapGrader.FurnaceTimerReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS — worst timer gap %d <= %d ticks over %d sampled furnace(s)",
                r.maxObservedGap(), r.toleranceTicks(), r.sampledPositions());
            case FAIL -> String.format(
                "FAIL — worst timer gap %d > %d ticks (%d/%d furnaces drift beyond tolerance)",
                r.maxObservedGap(), r.toleranceTicks(), r.divergedPositions(), r.sampledPositions());
            case INCONCLUSIVE -> "INCONCLUSIVE — no furnaces sampled "
                + "(no BE-FURNACE-TIMER lines: no furnace was smelting when the snapshot "
                + "fired, or the plugin emit is absent)";
        };
    }
}
