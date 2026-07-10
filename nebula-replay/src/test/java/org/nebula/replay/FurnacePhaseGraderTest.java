package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FurnacePhaseGraderTest {

    /**
     * Builds a BE-FURNACE-PHASE line via the real {@link FurnacePhaseFormatter}, so the parser
     * is tested against the byte-exact shape the plugin's phase-probe emit produces (the
     * producer/consumer round-trip guard).
     */
    private static String phaseLine(int tick, FurnacePhaseGrader.FurnacePhaseSample... positions) {
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + FurnacePhaseFormatter.format(tick, List.of(positions));
    }

    private static FurnacePhaseGrader.FurnacePhaseSample sample(
            WorldPos p, int foliaFuel, int foliaCook,
            int preFuel, int preCook, int postFuel, int postCook) {
        return new FurnacePhaseGrader.FurnacePhaseSample(
            p, foliaFuel, foliaCook, preFuel, preCook, postFuel, postCook);
    }

    private static final WorldPos A = new WorldPos(0, 8, -49, 8);
    private static final WorldPos B = new WorldPos(0, 5, 64, -3);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedAndAllThreeTimerSets() {
        String line = phaseLine(1234,
            sample(A, 1332, 42, 1332, 42, 1331, 43),
            sample(B, 100, 7, 98, 12, 97, 13));
        var parsed = FurnacePhaseGrader.parse(line);

        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        var p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals(1332, p0.foliaFuel());
        assertEquals(42, p0.foliaCook());
        assertEquals(1332, p0.preFuel());
        assertEquals(42, p0.preCook());
        assertEquals(1331, p0.postFuel());
        assertEquals(43, p0.postCook());
        // pre matched folia exactly → ordering artifact
        assertEquals(0, p0.preGap());
        // action stepped cook +1, fuel -1
        assertEquals(1, p0.cookStep());
        assertEquals(-1, p0.fuelStep());

        var p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals(2, p1.preFuelGap());  // |98-100|
        assertEquals(5, p1.preCookGap());  // |12-7|
        assertEquals(5, p1.preGap());
    }

    @Test
    void classifiesPureOrderingArtifactWhenPreMatchesFolia() {
        // THE decisive case this grader exists for: on every sample pre==folia exactly (the
        // sync rebased CAS onto Folia) and the only offset is the action's own +1/-1 step.
        // The BE-FURNACE-TIMER +1 is thus a pure ordering artifact, NOT a rate divergence.
        var snaps = FurnacePhaseGrader.parse(String.join("\n",
            phaseLine(100, sample(A, 1332, 42, 1332, 42, 1331, 43)),
            phaseLine(101, sample(A, 1331, 43, 1331, 43, 1330, 44)),
            phaseLine(102, sample(A, 1330, 44, 1330, 44, 1329, 45))));
        var report = FurnacePhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals("ORDERING-ARTIFACT", report.classification());
        assertEquals(0, report.maxPreGap());
        assertEquals(3, report.sampledPositions());
        assertEquals(0, report.rateDivergedPositions());
        // The action stepped exactly cook +1, fuel -1 on every sample.
        assertEquals(1, report.minCookStep());
        assertEquals(1, report.maxCookStep());
        assertEquals(-1, report.minFuelStep());
        assertEquals(-1, report.maxFuelStep());
    }

    @Test
    void classifiesRateDivergenceWhenPreStraysFromFolia() {
        // The trap: CAS drifted from Folia BEFORE the action ran — a gap syncFromNms should
        // have erased. That is a genuine rate divergence (the double-writer signature), so the
        // timers must be left to Folia; NOT a benign ordering lead.
        var snaps = FurnacePhaseGrader.parse(String.join("\n",
            phaseLine(100, sample(A, 1332, 42, 1330, 44, 1329, 45)),
            phaseLine(101, sample(A, 1330, 44, 1326, 48, 1325, 49))));
        var report = FurnacePhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals("RATE-DIVERGENCE", report.classification());
        assertEquals(2, report.rateDivergedPositions());
        assertTrue(report.maxPreGap() > 0);
    }

    @Test
    void toleranceAllowsSmallPreGap() {
        // pre off folia by 1: within tolerance 1 (still ordering-ish), beyond tolerance 0.
        var snaps = FurnacePhaseGrader.parse(
            phaseLine(100, sample(A, 1332, 42, 1332, 43, 1331, 44)));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            FurnacePhaseGrader.grade(snaps, 1).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            FurnacePhaseGrader.grade(snaps, 0).verdict());
    }

    @Test
    void preGapIsMaxAcrossBothAxesAndAllFurnaces() {
        var snaps = FurnacePhaseGrader.parse(
            phaseLine(100,
                sample(A, 1580, 10, 1583, 10, 1582, 11),   // pre fuel gap 3
                sample(B, 50, 20, 50, 27, 49, 28)));        // pre cook gap 7
        var report = FurnacePhaseGrader.grade(snaps, 2);
        assertEquals(3, report.maxPreFuelGap());
        assertEquals(7, report.maxPreCookGap());
        assertEquals(7, report.maxPreGap());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(2, report.rateDivergedPositions());  // both exceed tolerance 2
    }

    @Test
    void meanPreGapsAveragedOverAllSampledFurnaces() {
        var snaps = FurnacePhaseGrader.parse(
            phaseLine(100,
                sample(A, 12, 0, 10, 0, 9, 1),    // pre fuel gap 2, cook gap 0
                sample(B, 0, 0, 0, 4, 0, 5)));     // pre fuel gap 0, cook gap 4
        var report = FurnacePhaseGrader.grade(snaps, 100);
        assertEquals(1.0, report.meanPreFuelGap(), 1e-9);  // (2+0)/2
        assertEquals(2.0, report.meanPreCookGap(), 1e-9);  // (0+4)/2
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
    }

    @Test
    void ignoresOtherMarkerLines() {
        // A single log may carry BE-FURNACE-TIMER, BE-SETTLED and SETTLED-DIAG lines; none
        // carries the BE-FURNACE-PHASE marker, so all are filtered out.
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            phaseLine(1, sample(A, 1332, 42, 1332, 42, 1331, 43)),
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: tick=1 tracked=1 "
                + "positions=[FURNACE WorldPos[dimensionId=0, x=8, y=-49, z=8] nebulaFuel=1331 "
                + "foliaFuel=1332 nebulaCook=43 foliaCook=42]",
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: tick=1 tracked=1 "
                + "positions=[HOPPER WorldPos[dimensionId=0, x=0, y=64, z=0] nebula=48 folia=48]",
            phaseLine(2, sample(A, 1331, 43, 1331, 43, 1330, 44)));
        var parsed = FurnacePhaseGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void inconclusiveWhenNoFurnacesSampled() {
        var snaps = FurnacePhaseGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-PHASE: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = FurnacePhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals("NO-SAMPLES", report.classification());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
        // Step ranges collapse to 0 when nothing sampled (no bogus MAX_VALUE leak).
        assertEquals(0, report.minCookStep());
        assertEquals(0, report.maxCookStep());
    }

    @Test
    void parsesNegativeCoordinatesAndTimers() {
        String line = phaseLine(5, sample(C, 0, 0, 0, 0, 0, 0));
        var parsed = FurnacePhaseGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
    }

    @Test
    void formatterRoundTripsThroughParser() {
        // Anti-drift guard: the formatter's output, fed straight back through the real parser,
        // must reconstruct the samples exactly. A format change breaking the regex fails HERE,
        // not on a wasted live Folia run.
        List<FurnacePhaseGrader.FurnacePhaseSample> samples = List.of(
            sample(A, 1332, 42, 1332, 42, 1331, 43),
            sample(B, 100, 7, 98, 12, 97, 13),
            sample(C, 0, 0, 0, 0, 0, 0));
        String body = FurnacePhaseFormatter.format(777, samples);
        var parsed = FurnacePhaseGrader.parse(body);
        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(777, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(samples, snap.positions());
    }

    @Test
    void rejectsInvalidArguments() {
        List<FurnacePhaseGrader.FurnacePhaseSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> FurnacePhaseGrader.grade(empty, -1));
        assertThrows(IllegalArgumentException.class,
            () -> FurnacePhaseGrader.grade(null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> FurnacePhaseGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> FurnacePhaseGrader.parse((List<String>) null));
    }

    @Test
    void multipleFurnacesRenderAndParseInOrder() {
        List<FurnacePhaseGrader.FurnacePhaseSample> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(sample(new WorldPos(0, i, 64, 0),
                200 - i, i, 200 - i, i, 199 - i, i + 1));
        }
        var parsed = FurnacePhaseGrader.parse(
            phaseLine(50, specs.toArray(new FurnacePhaseGrader.FurnacePhaseSample[0])));
        assertEquals(5, parsed.get(0).positions().size());
        assertEquals(specs, parsed.get(0).positions());
    }
}
