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
 * Thin command-line front-end for {@link DropperSlotGapGrader} so a shell harness can grade
 * a driven run's {@code BE-DROPPER-SLOT} snapshots with the SAME tested logic the unit tests
 * exercise — rather than re-implementing the parse+grade in bash, which would fork a second
 * grader that could silently drift from this one (the project's defining wound). Mirrors
 * {@link FurnaceTimerGapGraderCli} and {@link BlockEntitySettledGraderCli}.
 *
 * <p>This class contains NO grading logic: it only reads the log, delegates to
 * {@link DropperSlotGapGrader#parse} / {@link DropperSlotGapGrader#grade}, prints the
 * report, and maps the verdict to a process exit code. The exit codes mirror the other
 * graders: {@code 0}=PASS, {@code 3}=FAIL, {@code 4}=INCONCLUSIVE, {@code 2}=usage/IO error.
 *
 * <p>The single tunable is {@code toleranceItems}, the largest per-position self-count gap
 * that still PASSes. Its honest default is {@code 0}: for the observe-only shadow to be a
 * safe eject write-back mirror it must track Folia's authoritative self count exactly, the
 * same precondition the entity Y-drift met before its vertical write-back was armed. A
 * nonzero tolerance is for characterizing HOW far off the shadow is, not for papering over a
 * real gap — the printed report always surfaces the observed max/mean gap so a loosened
 * tolerance can never hide the true divergence.
 *
 * <p>Usage:
 * <pre>
 *   java ... org.nebula.replay.DropperSlotGapGraderCli &lt;logFile|-&gt; [toleranceItems]
 * </pre>
 * {@code logFile} is a server-run.log path, or {@code -} to read stdin.
 */
public final class DropperSlotGapGraderCli {

    static final int DEFAULT_TOLERANCE_ITEMS = 0;

    static final int EXIT_PASS = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAIL = 3;
    static final int EXIT_INCONCLUSIVE = 4;

    private DropperSlotGapGraderCli() {
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
            err.println("usage: DropperSlotGapGraderCli <logFile|-> [toleranceItems]");
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

        DropperSlotGapGrader.DropperSlotReport report;
        try {
            report = DropperSlotGapGrader.grade(
                DropperSlotGapGrader.parse(lines), toleranceItems);
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
    static void printReport(DropperSlotGapGrader.DropperSlotReport r, PrintStream out) {
        out.println("--- Folia-vs-Nebula DROPPER-SLOT eject-gap grade (B8 C3) ---");
        out.println("Dropper snapshots:    " + r.snapshots());
        out.println("Droppers sampled:     " + r.sampledPositions());
        out.println("Diverged droppers:    " + r.divergedPositions());
        out.println("Max self-count gap:   " + r.maxObservedGap() + " items");
        out.printf ("Mean self-count gap:  %.4f items%n", r.meanGap());
        out.println("Tolerance:            " + r.toleranceItems() + " items");
        out.println("Verdict: " + verdictLine(r));
        out.println();
        out.println("NOTE: a pulsed dropper/dispenser STEPS its self count down by one per");
        out.println("      eject (vanilla getRandomSlot), so this grades the GAP magnitude");
        out.println("      between the observe-only shadow's summed self-slot count and");
        out.println("      Folia's, NOT settled equality. A gap of 0 is the safe-mirror");
        out.println("      precondition an eject write-back must meet BEFORE it is armed (the");
        out.println("      entity Y-drift met the same bar). The shadow eject is EJECT-ONLY");
        out.println("      into CAS, so this measures the SOURCE-slot draw cadence only — it");
        out.println("      is a MEASUREMENT, not proof dropper physics is correct.");
    }

    private static String verdictLine(DropperSlotGapGrader.DropperSlotReport r) {
        return switch (r.verdict()) {
            case PASS -> String.format(
                "PASS — worst self-count gap %d <= %d items over %d sampled dropper(s)",
                r.maxObservedGap(), r.toleranceItems(), r.sampledPositions());
            case FAIL -> String.format(
                "FAIL — worst self-count gap %d > %d items (%d/%d droppers drift beyond tolerance)",
                r.maxObservedGap(), r.toleranceItems(), r.divergedPositions(), r.sampledPositions());
            case INCONCLUSIVE -> "INCONCLUSIVE — no droppers sampled "
                + "(no BE-DROPPER-SLOT lines: no dropper/dispenser was pulsing when the "
                + "snapshot fired, or the plugin emit is absent)";
        };
    }
}
