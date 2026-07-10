package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure classifier for the furnace-timer {@code +1}/{@code -1} offset (B8 C3 correctness):
 * decides whether the observe-only shadow's one-tick lead over Folia is a harmless
 * <em>ordering artifact</em> or a genuine <em>rate divergence</em>. This is the diagnostic the
 * furnace-timer write-back decision hinges on — {@link FurnaceTimerGapGrader} MEASURED a
 * systematic gap of {@code 1} (800815b: nebulaCook {@code +1} ahead, nebulaFuel {@code -1}
 * behind), but a post-action-only sample cannot tell WHICH of two very different causes it is.
 *
 * <h3>The two hypotheses, and why they need three timers to separate</h3>
 * The block-entity DAG runs, on the owning region thread, {@code syncFromNms(self)} (which
 * rebases the CAS timers to Folia's authoritative values) and then the furnace action (which
 * does {@code cook_progress+1}, {@code fuel_time-1}) — every tick. Because CAS is rebased each
 * tick, a rate divergence can NEVER accumulate into a growing gap; it can only be caught by
 * comparing the model's per-tick advance to Folia's at a fixed phase. The phase probe captures,
 * on one pass:
 * <ul>
 *   <li>{@code folia} — Folia's authoritative timer (region-thread read).</li>
 *   <li>{@code pre} — CAS after {@code syncFromNms}, before the action.</li>
 *   <li>{@code post} — CAS after the action.</li>
 * </ul>
 * <b>ORDERING artifact (the benign case):</b> {@code pre == folia} exactly — the sync landed
 * CAS onto Folia — and the only offset is {@code post - pre}, the action's own single step
 * (cook {@code +1}, fuel {@code -1}). The shadow advances at Folia's rate; the BE-FURNACE-TIMER
 * {@code +1} was purely that the timer grader sampled {@code post} against a same-tick Folia
 * read. Under this verdict a guarded write-back sampled PRE-action could be honest.
 *
 * <p><b>RATE divergence (the trap):</b> {@code pre != folia} — CAS drifted from Folia BEFORE
 * the action, a gap {@code syncFromNms} should have erased, so the model's timer math advances
 * at a different rate than vanilla. Under this verdict the shadow is NOT a safe mirror and cook
 * must be left to Folia; arming write-back would be the double-writer divergence trap.
 *
 * <h3>Expected benign step per axis</h3>
 * For a burning, actively-smelting furnace the action's honest one-tick step is cook {@code +1}
 * and fuel {@code -1} (see {@code BlockEntityActions.furnace}). This classifier does NOT hard-code
 * that as a correctness oracle — it grades the PRE gap (which must be 0 for an ordering artifact)
 * and separately reports the observed post-step so a reader can confirm it matches the expected
 * action step. A furnace at rest (input consumed / output full) steps differently; those samples
 * carry {@code pre == post} on cook and are simply not evidence either way.
 *
 * <p>This class is pure: it parses {@code BE-FURNACE-PHASE:} lines and classifies the pre-action
 * gap against a tick tolerance. It performs no I/O. Marker {@code BE-FURNACE-PHASE:} is disjoint
 * from {@code BE-FURNACE-TIMER:}, {@code BE-SETTLED:} and {@code SETTLED-DIAG:} so one
 * {@code server-run.log} grades cleanly on every path.
 */
public final class FurnacePhaseGrader {

    /** Marker every furnace-phase snapshot line carries. */
    static final String PHASE_MARKER = "BE-FURNACE-PHASE:";

    private static final Pattern POSITION = Pattern.compile(
        "(\\w+) WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " foliaFuel=(-?\\d+) foliaCook=(-?\\d+)"
            + " preFuel=(-?\\d+) preCook=(-?\\d+)"
            + " postFuel=(-?\\d+) postCook=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private FurnacePhaseGrader() {
    }

    /**
     * One furnace's phase sample: Folia's authoritative timers, the CAS timers just after
     * {@code syncFromNms} (pre-action) and just after the DAG furnace action (post-action).
     * The decisive quantity is {@link #preGap()} — how far CAS strayed from Folia BEFORE the
     * action. Zero means the sync rebased exactly and the only offset is the action's own step.
     */
    public record FurnacePhaseSample(WorldPos pos, int foliaFuel, int foliaCook,
                                     int preFuel, int preCook,
                                     int postFuel, int postCook) {
        /** Absolute pre-action {@code fuel_time} gap between CAS and Folia. */
        public int preFuelGap() {
            return Math.abs(preFuel - foliaFuel);
        }

        /** Absolute pre-action {@code cook_progress} gap between CAS and Folia. */
        public int preCookGap() {
            return Math.abs(preCook - foliaCook);
        }

        /** The larger pre-action gap across both axes — the decisive per-furnace quantity. */
        public int preGap() {
            return Math.max(preFuelGap(), preCookGap());
        }

        /** The action's observed {@code fuel_time} step this tick ({@code post - pre}). */
        public int fuelStep() {
            return postFuel - preFuel;
        }

        /** The action's observed {@code cook_progress} step this tick ({@code post - pre}). */
        public int cookStep() {
            return postCook - preCook;
        }

        /**
         * {@code true} iff CAS strayed from Folia before the action ran by more than
         * {@code toleranceTicks} — i.e. this sample is evidence of a genuine RATE divergence,
         * not a benign ordering lead.
         */
        public boolean rateDiverged(int toleranceTicks) {
            return preGap() > toleranceTicks;
        }
    }

    /**
     * One snapshot as parsed from a {@code BE-FURNACE-PHASE:} line. {@code tracked} is the
     * plugin-reported count; the grader uses the parsed positions as ground truth.
     */
    public record FurnacePhaseSnapshot(int tick, int tracked, List<FurnacePhaseSample> positions) {
        public FurnacePhaseSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code BE-FURNACE-PHASE:} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<FurnacePhaseSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<FurnacePhaseSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(PHASE_MARKER)) {
                continue;
            }
            List<FurnacePhaseSample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
                positions.add(new FurnacePhaseSample(pos,
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)),
                    Integer.parseInt(m.group(8)),
                    Integer.parseInt(m.group(9)),
                    Integer.parseInt(m.group(10)),
                    Integer.parseInt(m.group(11))));
            }
            out.add(new FurnacePhaseSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<FurnacePhaseSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Classifies the furnace-timer offset across every snapshot's positions by grading the
     * PRE-action CAS-vs-Folia gap.
     *
     * @param snapshots      parsed snapshots, in log order
     * @param toleranceTicks the largest pre-action gap that still counts as "sync rebased
     *                       exactly" (an ordering artifact). {@code 0} demands CAS == Folia
     *                       before the action. Must be {@code >= 0}.
     * @return a report whose verdict is INCONCLUSIVE when no positions were sampled, PASS when
     *         every furnace's pre-action gap is within tolerance (the offset is a pure ordering
     *         artifact — a write-back sampled pre-action could be honest), else FAIL (a genuine
     *         rate divergence — leave the timers to Folia)
     */
    public static FurnacePhaseReport grade(List<FurnacePhaseSnapshot> snapshots, int toleranceTicks) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (toleranceTicks < 0) {
            throw new IllegalArgumentException("toleranceTicks must be >= 0");
        }
        int samples = 0;
        int rateDiverged = 0;
        int maxPreFuelGap = 0;
        int maxPreCookGap = 0;
        long sumPreFuelGap = 0;
        long sumPreCookGap = 0;
        int minCookStep = Integer.MAX_VALUE;
        int maxCookStep = Integer.MIN_VALUE;
        int minFuelStep = Integer.MAX_VALUE;
        int maxFuelStep = Integer.MIN_VALUE;
        for (FurnacePhaseSnapshot snap : snapshots) {
            for (FurnacePhaseSample p : snap.positions()) {
                samples++;
                maxPreFuelGap = Math.max(maxPreFuelGap, p.preFuelGap());
                maxPreCookGap = Math.max(maxPreCookGap, p.preCookGap());
                sumPreFuelGap += p.preFuelGap();
                sumPreCookGap += p.preCookGap();
                minCookStep = Math.min(minCookStep, p.cookStep());
                maxCookStep = Math.max(maxCookStep, p.cookStep());
                minFuelStep = Math.min(minFuelStep, p.fuelStep());
                maxFuelStep = Math.max(maxFuelStep, p.fuelStep());
                if (p.rateDiverged(toleranceTicks)) {
                    rateDiverged++;
                }
            }
        }
        if (samples == 0) {
            minCookStep = maxCookStep = minFuelStep = maxFuelStep = 0;
        }
        return new FurnacePhaseReport(snapshots.size(), samples, rateDiverged,
            maxPreFuelGap, maxPreCookGap, sumPreFuelGap, sumPreCookGap,
            minCookStep, maxCookStep, minFuelStep, maxFuelStep, toleranceTicks);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Classification result. The verdict grades the PRE-action gap (ordering vs rate), and the
     * observed cook/fuel step ranges are surfaced so a reader can confirm the action stepped by
     * the expected amount (cook {@code +1}, fuel {@code -1} for a burning furnace) rather than
     * inferring it.
     */
    public record FurnacePhaseReport(int snapshots, int sampledPositions, int rateDivergedPositions,
                                     int maxPreFuelGap, int maxPreCookGap,
                                     long sumPreFuelGap, long sumPreCookGap,
                                     int minCookStep, int maxCookStep,
                                     int minFuelStep, int maxFuelStep,
                                     int toleranceTicks) {
        /** The largest pre-action gap observed across both axes — the decisive quantity. */
        public int maxPreGap() {
            return Math.max(maxPreFuelGap, maxPreCookGap);
        }

        /** Mean pre-action {@code fuel_time} gap over sampled positions; 0.0 when none. */
        public double meanPreFuelGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumPreFuelGap / sampledPositions;
        }

        /** Mean pre-action {@code cook_progress} gap over sampled positions; 0.0 when none. */
        public double meanPreCookGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumPreCookGap / sampledPositions;
        }

        /** Fraction of furnaces whose pre-action gap exceeds tolerance; 0.0 when none. */
        public double rateDivergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) rateDivergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return maxPreGap() <= toleranceTicks
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }

        /**
         * A one-word plain-English label for the verdict: {@code ORDERING-ARTIFACT} (PASS),
         * {@code RATE-DIVERGENCE} (FAIL) or {@code NO-SAMPLES} (INCONCLUSIVE).
         */
        public String classification() {
            return switch (verdict()) {
                case PASS -> "ORDERING-ARTIFACT";
                case FAIL -> "RATE-DIVERGENCE";
                case INCONCLUSIVE -> "NO-SAMPLES";
            };
        }
    }
}
