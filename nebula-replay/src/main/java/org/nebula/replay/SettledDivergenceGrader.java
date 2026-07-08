package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grader for the <em>settled-state</em> Folia-vs-Nebula divergence signal
 * (DG3 correctness slice; the load-bearing complement to {@link FoliaDivergenceGrader}).
 *
 * <h3>Why a second grader — and why the first one cannot carry this signal</h3>
 * {@link FoliaDivergenceGrader} grades the residual dirty rate of the
 * {@code CASCADE-DIAG} stream, which is emitted <em>only</em> when a redstone
 * position is re-seeded (a {@code BLOCK_UPDATE} fires). Under a driven square wave
 * the line never settles, so almost every seed is sampled mid-propagation, where
 * Nebula's observe-only shadow is legitimately one cycle behind Folia — a high dirty
 * rate that is expected lag, not a bug (see that class's javadoc, and the live
 * evidence recorded 2026-07-09: 2892/2967 samples dirty, all topology-shaped lag).
 *
 * <p>The trap the empirical data exposes: in a real driven run every {@code (settled)}
 * sample was {@code cas=0→nms=0} and every settled sample had {@code microsteps==0},
 * so {@code (settled) ⟺ microsteps==0 ⟺ cas==nms} at 100%. Filtering the existing
 * CASCADE-DIAG stream to "settled-only" would therefore report a divergence rate of
 * exactly 0 <em>by construction</em> — a fake PASS. The reason is structural:
 * <strong>steady-state ON fires no {@code BLOCK_UPDATE}s, so a fully-powered settled
 * wire is never seeded into {@code executeOwnedDag} and never appears in the seed
 * stream at all.</strong> The seed stream can only ever show agreement during the
 * wave's brief all-off phase.
 *
 * <h3>The signal this grader consumes</h3>
 * The load-bearing settled-state signal needs a distinct surface: once a circuit has
 * been driven to quiescence (no pending neighbour updates, {@code microsteps==0}
 * sustained), the plugin snapshots <em>every tracked position</em> and logs a
 * {@code SETTLED-DIAG} line comparing Nebula's CAS store value ({@code nebula=X})
 * against Folia's authoritative power ({@code folia=Y}) for each. At true quiescence
 * there is no observe-lag excuse: the shadow has had every intervening tick to catch
 * up, so <strong>any {@code nebula != folia} is a genuine Folia-vs-Nebula divergence,
 * not boundary lag.</strong> That is what makes the settled snapshot a load-bearing
 * correctness signal where the residual dirty rate is not.
 *
 * <p>This class is pure: it parses {@code SETTLED-DIAG} lines and grades the
 * divergence rate against a threshold. The default threshold is {@code 0.0} — at
 * genuine quiescence a correct shadow matches Folia exactly. It performs no I/O and
 * knows nothing about how the snapshot is produced; the plugin emit + CLI + live run
 * are a separate slice (mirroring how {@link FoliaDivergenceGrader} shipped as a pure
 * core before its live harness).
 */
public final class SettledDivergenceGrader {

    /** Marker every settled-snapshot line carries. */
    static final String SETTLED_MARKER = "SETTLED-DIAG:";

    // One tracked position rendered by the plugin, e.g.
    //   WorldPos[dimensionId=0, x=1, y=-60, z=2] nebula=15 folia=15
    // Matching each position sample directly (rather than splitting the positions=[...]
    // list on commas) is robust to the commas inside the WorldPos record rendering,
    // exactly as FoliaDivergenceGrader.SEED does for the cas→nms stream.
    private static final Pattern POSITION = Pattern.compile(
        "WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " nebula=(-?\\d+) folia=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private SettledDivergenceGrader() {
    }

    /**
     * One tracked position's settled-state sample. {@link #diverged()} is the
     * correctness bit: at quiescence {@code nebula != folia} is a real divergence.
     */
    public record PositionSample(WorldPos pos, int nebula, int folia) {
        public boolean diverged() {
            return nebula != folia;
        }
    }

    /**
     * One quiescence snapshot as parsed from a {@code SETTLED-DIAG} line. The
     * {@code tracked} field is the plugin-reported tracked-position count; it may
     * exceed {@code positions.size()} only if a rendering was truncated, so the
     * grader uses the parsed positions as ground truth and surfaces {@code tracked}
     * for cross-checking.
     */
    public record SettledSnapshot(int tick, int tracked, List<PositionSample> positions) {
        public SettledSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code SETTLED-DIAG} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<SettledSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<SettledSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(SETTLED_MARKER)) {
                continue;
            }
            List<PositionSample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)));
                positions.add(new PositionSample(pos,
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6))));
            }
            out.add(new SettledSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<SettledSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Grades the settled-state divergence rate across every snapshot's positions.
     *
     * @param snapshots       parsed settled snapshots, in log order
     * @param maxDivergenceRate the pass threshold on the divergence rate, in [0,1];
     *                          {@code 0.0} means "exact agreement required at
     *                          quiescence" (the honest default — see class javadoc)
     * @return a report whose {@link SettledReport#verdict()} is INCONCLUSIVE when no
     *         positions were sampled, else PASS/FAIL against the threshold
     */
    public static SettledReport grade(List<SettledSnapshot> snapshots, double maxDivergenceRate) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (maxDivergenceRate < 0.0 || maxDivergenceRate > 1.0) {
            throw new IllegalArgumentException("maxDivergenceRate must be in [0,1]");
        }
        int samples = 0;
        int diverged = 0;
        for (SettledSnapshot snap : snapshots) {
            for (PositionSample p : snap.positions()) {
                samples++;
                if (p.diverged()) {
                    diverged++;
                }
            }
        }
        return new SettledReport(snapshots.size(), samples, diverged, maxDivergenceRate);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Grading result for the settled-state check. Unlike
     * {@link FoliaDivergenceGrader.DivergenceReport}, there is no converge window:
     * every graded sample is taken <em>at</em> quiescence, so there is no cold-start
     * transient to exclude — a diverged sample is a real divergence.
     */
    public record SettledReport(int snapshots, int sampledPositions, int divergedPositions,
                                double maxDivergenceRate) {
        /** Divergence rate over sampled positions; 0.0 when there are none. */
        public double divergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) divergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return divergenceRate() <= maxDivergenceRate
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }
    }
}
