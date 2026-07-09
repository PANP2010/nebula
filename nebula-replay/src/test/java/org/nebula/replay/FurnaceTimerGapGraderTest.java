package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FurnaceTimerGapGraderTest {

    /**
     * Builds a BE-FURNACE-TIMER line via the real {@link FurnaceTimerFormatter}, so the
     * parser is tested against the byte-exact shape the plugin's furnace-timer emit will
     * produce (this is also the producer/consumer round-trip guard).
     */
    private static String timerLine(int tick, FurnaceTimerGapGrader.FurnaceTimerSample... positions) {
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + FurnaceTimerFormatter.format(tick, List.of(positions));
    }

    private static FurnaceTimerGapGrader.FurnaceTimerSample sample(
            WorldPos p, int nFuel, int fFuel, int nCook, int fCook) {
        return new FurnaceTimerGapGrader.FurnaceTimerSample(p, nFuel, fFuel, nCook, fCook);
    }

    private static final WorldPos A = new WorldPos(0, 0, 64, 0);
    private static final WorldPos B = new WorldPos(0, 5, 64, -3);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedAndBothTimerAxes() {
        String line = timerLine(1234,
            sample(A, 1580, 1580, 42, 42),
            sample(B, 100, 98, 7, 12));
        var parsed = FurnaceTimerGapGrader.parse(line);

        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        var p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals(1580, p0.nebulaFuel());
        assertEquals(1580, p0.foliaFuel());
        assertEquals(42, p0.nebulaCook());
        assertEquals(42, p0.foliaCook());
        assertEquals(0, p0.maxGap());

        var p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals(2, p1.fuelGap());
        assertEquals(5, p1.cookGap());
        assertEquals(5, p1.maxGap());
    }

    @Test
    void parsesNegativeCoordinatesAndTimers() {
        String line = timerLine(5, sample(C, 0, 0, 0, 0));
        var parsed = FurnaceTimerGapGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
    }

    @Test
    void ignoresNonFurnaceTimerLines() {
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            timerLine(1, sample(A, 1580, 1580, 42, 42)),
            // A BE-SETTLED (inventory) line must NOT be picked up by the timer grader.
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: tick=1 tracked=1 "
                + "positions=[HOPPER WorldPos[dimensionId=0, x=0, y=64, z=0] nebula=48 folia=48]",
            timerLine(2, sample(A, 1579, 1579, 43, 43)));
        var parsed = FurnaceTimerGapGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void doesNotCollideWithBeSettledOrRedstoneLines() {
        // A single log may carry all three graders' lines. Neither BE-SETTLED nor
        // SETTLED-DIAG carries the BE-FURNACE-TIMER marker, so both are filtered out.
        String beSettled = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: "
            + "tick=100 tracked=1 positions=[HOPPER WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebula=15 folia=15]";
        String redstone = "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: "
            + "tick=100 tracked=1 positions=[WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebula=15 folia=15]";
        assertTrue(FurnaceTimerGapGrader.parse(beSettled).isEmpty()
            || FurnaceTimerGapGrader.parse(beSettled).get(0).positions().isEmpty());
        assertTrue(FurnaceTimerGapGrader.parse(redstone).isEmpty());
    }

    @Test
    void passesWhenEveryFurnaceTracksFoliaExactly() {
        var snaps = FurnaceTimerGapGrader.parse(String.join("\n",
            timerLine(100, sample(A, 1580, 1580, 42, 42), sample(B, 0, 0, 0, 0)),
            timerLine(200, sample(A, 1380, 1380, 199, 199), sample(B, 0, 0, 0, 0))));
        var report = FurnaceTimerGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0, report.maxObservedGap());
        assertEquals(4, report.sampledPositions());
        assertEquals(0, report.divergedPositions());
        assertEquals(2, report.snapshots());
        assertEquals(0.0, report.meanCookGap(), 1e-9);
    }

    @Test
    void failsOnAnyGapUnderZeroTolerance() {
        // A single cook_progress off-by-one is a FAIL at tolerance 0 — the safe-mirror bar.
        var snaps = FurnaceTimerGapGrader.parse(
            timerLine(100, sample(A, 1580, 1580, 43, 42)));
        var report = FurnaceTimerGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(1, report.divergedPositions());
        assertEquals(1, report.maxCookGap());
        assertEquals(0, report.maxFuelGap());
    }

    @Test
    void toleranceAllowsSmallGap() {
        // cook off by 1: within tolerance 1 (PASS), beyond tolerance 0 (FAIL).
        var snaps = FurnaceTimerGapGrader.parse(
            timerLine(100, sample(A, 1580, 1580, 43, 42)));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            FurnaceTimerGapGrader.grade(snaps, 1).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            FurnaceTimerGapGrader.grade(snaps, 0).verdict());
    }

    @Test
    void worstGapIsMaxAcrossBothAxesAndAllFurnaces() {
        // fuel gap 3 at A, cook gap 7 at B → worst observed is 7; a double-write furnace
        // (≈2x advance) would show a large, growing gap exactly like this.
        var snaps = FurnaceTimerGapGrader.parse(
            timerLine(100, sample(A, 1580, 1583, 10, 10), sample(B, 50, 50, 20, 27)));
        var report = FurnaceTimerGapGrader.grade(snaps, 2);
        assertEquals(3, report.maxFuelGap());
        assertEquals(7, report.maxCookGap());
        assertEquals(7, report.maxObservedGap());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(2, report.divergedPositions());  // both exceed tolerance 2
    }

    @Test
    void meanGapsAveragedOverAllSampledFurnaces() {
        var snaps = FurnaceTimerGapGrader.parse(
            timerLine(100, sample(A, 10, 12, 0, 0), sample(B, 0, 0, 4, 0)));
        var report = FurnaceTimerGapGrader.grade(snaps, 100);
        // fuel gaps 2,0 → mean 1.0 ; cook gaps 0,4 → mean 2.0
        assertEquals(1.0, report.meanFuelGap(), 1e-9);
        assertEquals(2.0, report.meanCookGap(), 1e-9);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
    }

    @Test
    void inconclusiveWhenNoFurnacesSampled() {
        var snaps = FurnaceTimerGapGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = FurnaceTimerGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
    }

    @Test
    void formatterRoundTripsThroughParser() {
        // The load-bearing anti-drift guard: the formatter's output, fed straight back
        // through the real parser, must reconstruct the samples exactly. A format change
        // that breaks the regex fails HERE, not on a wasted live Folia run.
        List<FurnaceTimerGapGrader.FurnaceTimerSample> samples = List.of(
            sample(A, 1580, 1580, 42, 42),
            sample(B, 100, 98, 7, 12),
            sample(C, 0, 0, 0, 0));
        String body = FurnaceTimerFormatter.format(777, samples);
        var parsed = FurnaceTimerGapGrader.parse(body);
        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(777, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(samples, snap.positions());
    }

    @Test
    void rejectsInvalidArguments() {
        List<FurnaceTimerGapGrader.FurnaceTimerSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> FurnaceTimerGapGrader.grade(empty, -1));
        assertThrows(IllegalArgumentException.class,
            () -> FurnaceTimerGapGrader.grade(null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> FurnaceTimerGapGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> FurnaceTimerGapGrader.parse((List<String>) null));
    }

    @Test
    void emptyLogParsesToNothingAndGradesInconclusive() {
        var parsed = FurnaceTimerGapGrader.parse(List.of());
        assertTrue(parsed.isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            FurnaceTimerGapGrader.grade(parsed, 0).verdict());
    }

    @Test
    void multipleFurnacesRenderAndParseInOrder() {
        List<FurnaceTimerGapGrader.FurnaceTimerSample> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(sample(new WorldPos(0, i, 64, 0), 200 - i, 200 - i, i, i));
        }
        var parsed = FurnaceTimerGapGrader.parse(
            timerLine(50, specs.toArray(new FurnaceTimerGapGrader.FurnaceTimerSample[0])));
        assertEquals(5, parsed.get(0).positions().size());
        assertEquals(specs, parsed.get(0).positions());
    }
}
