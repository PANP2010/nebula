package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grader for the observe-only <em>dropper/dispenser eject gap</em> signal (B8 C3
 * correctness): how far Nebula's shadow CAS self-inventory item count strays from Folia's
 * authoritative count on a ticking dropper or dispenser. This is the eject twin of
 * {@link FurnaceTimerGapGrader} (which grades furnace timers) and shares its GAP shape
 * rather than {@link BlockEntitySettledGrader}'s settled-equality shape — for the reason
 * below.
 *
 * <h3>Why a GAP grader and not a settled-equality grader</h3>
 * A hopper's inventory count <em>settles</em> to a resting value once its feed stops, so
 * {@link BlockEntitySettledGrader} can demand exact {@code nebula == folia} at quiescence.
 * A dropper's does not settle in the same clean way while it is being pulsed: vanilla's
 * {@code getRandomSlot} draws one non-empty slot per pulse and removes one item, so the
 * self count steps down by one each eject. Nebula's shadow does the identical reservoir
 * draw ({@link org.nebula.entity.actions.BlockEntityActions#dropper}) against its CAS
 * copy of the slots. The honest correctness surface is therefore the <em>magnitude of the
 * per-sample gap</em> between the shadow's summed self count and Folia's — the block-entity
 * analogue of the furnace timer gap and of {@link org.nebula.entity.EntityDivergenceTracker}'s
 * per-frame drift. A gap of 0 means the observe-only shadow's eject cadence tracks Folia
 * item-for-item (the safe-mirror precondition); a nonzero gap quantifies exactly how far
 * off the shadow's draw is, so a later cycle can decide whether the eject write-back is
 * worth arming or whether the dropper should be left to Folia entirely.
 *
 * <p>It is a MEASUREMENT, not a "dropper physics is correct" proof — do not read a small
 * gap as "the DAG dropper matches vanilla." In particular the shadow's eject is
 * EJECT-ONLY into CAS (the ejected item vanishes rather than landing in the faced
 * container; see {@code BlockEntityActions.dropper}'s javadoc), so a gap here reflects
 * only the <em>source</em>-slot draw cadence, which is exactly the quantity a write-back
 * arm would have to mirror.
 *
 * <h3>Why this is the honest first slice, and not the write-back arm</h3>
 * Arming a dropper write-back that pushes the shadow's decremented slot back onto the live
 * tile is a DOUBLE-WRITER trap, the same one {@link FurnaceTimerGapGrader}'s javadoc
 * describes for timers: Folia already ejects authoritatively every pulse AND the DAG would
 * too, over-ejecting the source. The entity path only armed its vertical mirror AFTER
 * {@link org.nebula.entity.EntityDivergenceTracker} proved the drift ≈0 live. This grader
 * is the equivalent measurement instrument for dropper ejects: it must exist and grade the
 * live gap ≈0 BEFORE any write-back arm is honest. "Measure before you mirror."
 *
 * <p>This class is pure: it parses {@code BE-DROPPER-SLOT:} lines and grades the gap
 * against an item-count tolerance. It performs no I/O and knows nothing about how the
 * snapshot is produced; the plugin emit + live run are a separate slice, exactly as
 * {@link FurnaceTimerGapGrader} shipped as a pure core before its live harness.
 *
 * <h3>Marker isolation from the other graders</h3>
 * The marker is {@code BE-DROPPER-SLOT:}, deliberately NOT a substring of
 * {@code BE-FURNACE-TIMER:}, {@code BE-SETTLED:} or {@code SETTLED-DIAG:}, and vice versa.
 * Each grader filters by its own marker before parsing, so a single {@code server-run.log}
 * carrying all four line kinds grades cleanly on each path with no cross-contamination.
 */
public final class DropperSlotGapGrader {

    /** Marker every dropper-slot-gap snapshot line carries. */
    static final String DROPPER_MARKER = "BE-DROPPER-SLOT:";

    // One tracked dropper/dispenser rendered by the plugin, e.g.
    //   DROPPER WorldPos[dimensionId=0, x=100, y=64, z=100] nebula=8 folia=8
    // The leading \w+ captures the block-entity type (DROPPER or DISPENSER); matching each
    // position directly is robust to the commas inside the WorldPos record rendering,
    // exactly as BlockEntitySettledGrader.POSITION does.
    private static final Pattern POSITION = Pattern.compile(
        "(\\w+) WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " nebula=(-?\\d+) folia=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private DropperSlotGapGrader() {
    }

    /**
     * One dropper/dispenser's eject sample: the shadow CAS summed self-slot count vs
     * Folia's authoritative summed self-slot count. {@link #gap()} is the absolute
     * difference — the decisive quantity for whether an eject write-back mirror would be
     * safe at this position.
     */
    public record DropperSlotSample(WorldPos pos, String type, int nebula, int folia) {
        /** Absolute gap between the shadow's and Folia's summed self-inventory count. */
        public int gap() {
            return Math.abs(nebula - folia);
        }

        /** {@code true} iff this position's gap exceeds {@code toleranceItems}. */
        public boolean diverged(int toleranceItems) {
            return gap() > toleranceItems;
        }
    }

    /**
     * One snapshot as parsed from a {@code BE-DROPPER-SLOT:} line. {@code tracked} is the
     * plugin-reported count; the grader uses the parsed positions as ground truth and
     * surfaces {@code tracked} for cross-checking.
     */
    public record DropperSlotSnapshot(int tick, int tracked, List<DropperSlotSample> positions) {
        public DropperSlotSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code BE-DROPPER-SLOT:} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<DropperSlotSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<DropperSlotSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(DROPPER_MARKER)) {
                continue;
            }
            List<DropperSlotSample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
                positions.add(new DropperSlotSample(pos, m.group(1),
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7))));
            }
            out.add(new DropperSlotSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<DropperSlotSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Grades the dropper/dispenser eject gap across every snapshot's positions.
     *
     * @param snapshots      parsed snapshots, in log order
     * @param toleranceItems the largest per-position self-count gap (in items) that still
     *                       PASSes; {@code 0} means "the shadow must match Folia's self
     *                       count exactly" (the honest safe-mirror precondition — see class
     *                       javadoc). Must be {@code >= 0}.
     * @return a report whose verdict is INCONCLUSIVE when no positions were sampled, else
     *         PASS when every position's gap is within tolerance, else FAIL
     */
    public static DropperSlotReport grade(List<DropperSlotSnapshot> snapshots, int toleranceItems) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (toleranceItems < 0) {
            throw new IllegalArgumentException("toleranceItems must be >= 0");
        }
        int samples = 0;
        int diverged = 0;
        int maxGap = 0;
        long sumGap = 0;
        for (DropperSlotSnapshot snap : snapshots) {
            for (DropperSlotSample p : snap.positions()) {
                samples++;
                maxGap = Math.max(maxGap, p.gap());
                sumGap += p.gap();
                if (p.diverged(toleranceItems)) {
                    diverged++;
                }
            }
        }
        return new DropperSlotReport(snapshots.size(), samples, diverged, maxGap, sumGap, toleranceItems);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Grading result for the dropper/dispenser eject-gap check. Carries the gap
     * <em>magnitude</em> (max and mean), because for a stepping self count it is the size
     * of the gap — not merely its presence — that decides whether an eject write-back
     * mirror could ever be safe (a double-ejector would show a large, growing gap).
     */
    public record DropperSlotReport(int snapshots, int sampledPositions, int divergedPositions,
                                    int maxGap, long sumGap, int toleranceItems) {
        /** The largest single self-count gap observed — the decisive quantity. */
        public int maxObservedGap() {
            return maxGap;
        }

        /** Mean self-count gap over sampled positions; 0.0 when there are none. */
        public double meanGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumGap / sampledPositions;
        }

        /** Fraction of positions whose gap exceeds tolerance; 0.0 when none sampled. */
        public double divergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) divergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return maxObservedGap() <= toleranceItems
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }
    }
}
