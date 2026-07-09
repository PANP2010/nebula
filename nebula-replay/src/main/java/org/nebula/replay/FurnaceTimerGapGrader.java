package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grader for the observe-only <em>furnace-timer gap</em> signal (B8 C3 correctness):
 * how far Nebula's shadow CAS furnace timers ({@code fuel_time}, {@code cook_progress})
 * stray from Folia's authoritative timers. This is the timer twin of
 * {@link BlockEntitySettledGrader} (which grades hopper <em>inventory counts</em> at
 * quiescence) and the block-entity analogue of {@link org.nebula.entity.EntityDivergenceTracker}
 * (which measures the entity model's per-frame position drift).
 *
 * <h3>Why a GAP grader and not a settled-equality grader</h3>
 * The hopper inventory count <em>settles</em> to a stable resting value once its feed
 * stops, so {@link BlockEntitySettledGrader} can demand exact {@code nebula == folia} at
 * quiescence. Furnace timers do NOT settle while a furnace is smelting: {@code cook_progress}
 * climbs 0→{@code COOK_TOTAL} and resets to 0 every smelt, and {@code fuel_time} counts a
 * fuel item down and refills on the next. There is no resting value to compare — the honest
 * surface is instead the <em>magnitude of the per-sample gap</em> between the shadow's
 * timers and Folia's. A gap of 0 means the observe-only shadow tracks Folia tick-for-tick
 * (the safe-mirror precondition the entity Y-drift met before its write-back was armed); a
 * nonzero gap quantifies exactly how far off the shadow is, so a later cycle can decide
 * whether a phase-offset correction is worth building or whether the timers should be left
 * to Folia entirely. It is a MEASUREMENT, not a "furnace physics is correct" proof — do not
 * read a small gap as "the DAG furnace matches vanilla."
 *
 * <h3>Why this is the honest first slice, and not the write-back arm</h3>
 * Arming {@code syncFurnaceToNms} to push the DAG's {@code cook_progress + 1} back onto the
 * live tile is a DOUBLE-WRITER trap: Folia advances the timer authoritatively every game
 * tick AND the DAG would too, over-advancing it (≈2× cook speed = divergence, not
 * convergence). The entity path only armed its vertical mirror AFTER
 * {@link org.nebula.entity.EntityDivergenceTracker} proved the drift ≈0 live. This grader is
 * the equivalent measurement instrument for furnace timers: it must exist and grade the live
 * gap ≈0 BEFORE any write-back arm is honest. "Measure before you mirror."
 *
 * <p>This class is pure: it parses {@code BE-FURNACE-TIMER:} lines and grades the gap
 * against a tick tolerance. It performs no I/O and knows nothing about how the snapshot is
 * produced; the plugin emit + live run are a separate slice, exactly as
 * {@link BlockEntitySettledGrader} shipped as a pure core before its live harness.
 *
 * <h3>Marker isolation from the other graders</h3>
 * The marker is {@code BE-FURNACE-TIMER:}, deliberately NOT a substring of either
 * {@code BE-SETTLED:} (the hopper inventory grader) or {@code SETTLED-DIAG:} (the redstone
 * grader), and vice versa. Each grader filters by its own marker before parsing, so a single
 * {@code server-run.log} carrying all three line kinds grades cleanly on each path with no
 * cross-contamination.
 */
public final class FurnaceTimerGapGrader {

    /** Marker every furnace-timer-gap snapshot line carries. */
    static final String TIMER_MARKER = "BE-FURNACE-TIMER:";

    // One furnace rendered by the plugin, e.g.
    //   FURNACE WorldPos[dimensionId=0, x=0, y=64, z=0] nebulaFuel=1580 foliaFuel=1580 nebulaCook=42 foliaCook=42
    // The leading \w+ captures the type (always FURNACE here, but kept for symmetry with the
    // other block-entity line formats and to reuse the WorldPos regex shape). Matching each
    // position directly is robust to the commas inside the WorldPos record rendering.
    private static final Pattern POSITION = Pattern.compile(
        "(\\w+) WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " nebulaFuel=(-?\\d+) foliaFuel=(-?\\d+) nebulaCook=(-?\\d+) foliaCook=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private FurnaceTimerGapGrader() {
    }

    /**
     * One furnace's timer sample: the shadow CAS timers vs Folia's authoritative timers.
     * {@link #fuelGap()} / {@link #cookGap()} are the absolute per-timer differences and
     * {@link #maxGap()} the larger of the two — the decisive quantity for whether a
     * write-back mirror would be safe at this position.
     */
    public record FurnaceTimerSample(WorldPos pos, int nebulaFuel, int foliaFuel,
                                     int nebulaCook, int foliaCook) {
        /** Absolute gap between shadow and Folia {@code fuel_time}. */
        public int fuelGap() {
            return Math.abs(nebulaFuel - foliaFuel);
        }

        /** Absolute gap between shadow and Folia {@code cook_progress}. */
        public int cookGap() {
            return Math.abs(nebulaCook - foliaCook);
        }

        /** The larger of the two timer gaps — a single furnace's worst-axis divergence. */
        public int maxGap() {
            return Math.max(fuelGap(), cookGap());
        }

        /** {@code true} iff this furnace's worst timer gap exceeds {@code toleranceTicks}. */
        public boolean diverged(int toleranceTicks) {
            return maxGap() > toleranceTicks;
        }
    }

    /**
     * One snapshot as parsed from a {@code BE-FURNACE-TIMER:} line. {@code tracked} is the
     * plugin-reported count; the grader uses the parsed positions as ground truth and
     * surfaces {@code tracked} for cross-checking.
     */
    public record FurnaceTimerSnapshot(int tick, int tracked, List<FurnaceTimerSample> positions) {
        public FurnaceTimerSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code BE-FURNACE-TIMER:} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<FurnaceTimerSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<FurnaceTimerSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(TIMER_MARKER)) {
                continue;
            }
            List<FurnaceTimerSample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
                positions.add(new FurnaceTimerSample(pos,
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)),
                    Integer.parseInt(m.group(8)),
                    Integer.parseInt(m.group(9))));
            }
            out.add(new FurnaceTimerSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<FurnaceTimerSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Grades the furnace-timer gap across every snapshot's positions.
     *
     * @param snapshots      parsed snapshots, in log order
     * @param toleranceTicks the largest per-furnace timer gap (in ticks) that still PASSes;
     *                       {@code 0} means "the shadow must match Folia's timers exactly"
     *                       (the honest safe-mirror precondition — see class javadoc). Must
     *                       be {@code >= 0}.
     * @return a report whose verdict is INCONCLUSIVE when no positions were sampled, else
     *         PASS when every furnace's worst gap is within tolerance, else FAIL
     */
    public static FurnaceTimerReport grade(List<FurnaceTimerSnapshot> snapshots, int toleranceTicks) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (toleranceTicks < 0) {
            throw new IllegalArgumentException("toleranceTicks must be >= 0");
        }
        int samples = 0;
        int diverged = 0;
        int maxFuelGap = 0;
        int maxCookGap = 0;
        long sumFuelGap = 0;
        long sumCookGap = 0;
        for (FurnaceTimerSnapshot snap : snapshots) {
            for (FurnaceTimerSample p : snap.positions()) {
                samples++;
                maxFuelGap = Math.max(maxFuelGap, p.fuelGap());
                maxCookGap = Math.max(maxCookGap, p.cookGap());
                sumFuelGap += p.fuelGap();
                sumCookGap += p.cookGap();
                if (p.diverged(toleranceTicks)) {
                    diverged++;
                }
            }
        }
        return new FurnaceTimerReport(snapshots.size(), samples, diverged,
            maxFuelGap, maxCookGap, sumFuelGap, sumCookGap, toleranceTicks);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Grading result for the furnace-timer gap check. Unlike the inventory settled grader
     * (whose divergence is binary per position) this carries the gap <em>magnitudes</em>
     * (max and mean per timer), because for a cyclic timer it is the size of the gap — not
     * merely its presence — that decides whether a write-back mirror could ever be safe.
     */
    public record FurnaceTimerReport(int snapshots, int sampledPositions, int divergedPositions,
                                     int maxFuelGap, int maxCookGap, long sumFuelGap, long sumCookGap,
                                     int toleranceTicks) {
        /** The largest single timer gap observed across both axes — the decisive quantity. */
        public int maxObservedGap() {
            return Math.max(maxFuelGap, maxCookGap);
        }

        /** Mean {@code fuel_time} gap over sampled positions; 0.0 when there are none. */
        public double meanFuelGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumFuelGap / sampledPositions;
        }

        /** Mean {@code cook_progress} gap over sampled positions; 0.0 when there are none. */
        public double meanCookGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumCookGap / sampledPositions;
        }

        /** Fraction of furnaces whose worst gap exceeds tolerance; 0.0 when none sampled. */
        public double divergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) divergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return maxObservedGap() <= toleranceTicks
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }
    }
}
