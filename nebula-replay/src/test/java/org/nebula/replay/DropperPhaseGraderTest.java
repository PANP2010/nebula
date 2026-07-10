package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DropperPhaseGraderTest {

    /**
     * Builds a BE-DROPPER-PHASE line via the real {@link DropperPhaseFormatter}, so the parser
     * is tested against the byte-exact shape the plugin's phase-probe emit produces (the
     * producer/consumer round-trip guard).
     */
    private static String phaseLine(int tick, DropperPhaseGrader.DropperPhaseSample... positions) {
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + DropperPhaseFormatter.format(tick, List.of(positions));
    }

    private static DropperPhaseGrader.DropperPhaseSample sample(
            WorldPos p, String type, int foliaSelf, int preSelf, int postSelf) {
        return new DropperPhaseGrader.DropperPhaseSample(p, type, foliaSelf, preSelf, postSelf);
    }

    private static final WorldPos A = new WorldPos(0, 100, 64, 100);
    private static final WorldPos B = new WorldPos(0, 5, 64, -3);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedTypeAndAllThreeCounts() {
        String line = phaseLine(1234,
            sample(A, "DROPPER", 8, 8, 7),
            sample(B, "DISPENSER", 10, 6, 5));
        var parsed = DropperPhaseGrader.parse(line);

        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        var p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals("DROPPER", p0.type());
        assertEquals(8, p0.foliaSelf());
        assertEquals(8, p0.preSelf());
        assertEquals(7, p0.postSelf());
        // pre matched folia exactly → ordering artifact; action stepped -1
        assertEquals(0, p0.preGap());
        assertEquals(-1, p0.selfStep());

        var p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals("DISPENSER", p1.type());
        assertEquals(4, p1.preGap());   // |6-10|
        assertEquals(-1, p1.selfStep()); // 5-6
    }

    @Test
    void classifiesPureOrderingArtifactWhenPreMatchesFolia() {
        // THE decisive case this grader exists for: on every sample preSelf==foliaSelf exactly
        // (the sync rebased CAS onto Folia) and the only offset is the action's own -1 eject
        // step. The BE-DROPPER-SLOT +1 is thus a pure ordering artifact, NOT a rate divergence.
        var snaps = DropperPhaseGrader.parse(String.join("\n",
            phaseLine(100, sample(A, "DROPPER", 8, 8, 7)),
            phaseLine(101, sample(A, "DROPPER", 7, 7, 6)),
            phaseLine(102, sample(A, "DROPPER", 6, 6, 5))));
        var report = DropperPhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals("ORDERING-ARTIFACT", report.classification());
        assertEquals(0, report.maxObservedPreGap());
        assertEquals(3, report.sampledPositions());
        assertEquals(0, report.rateDivergedPositions());
        // The action stepped exactly -1 on every sample.
        assertEquals(-1, report.minSelfStep());
        assertEquals(-1, report.maxSelfStep());
    }

    @Test
    void classifiesRateDivergenceWhenPreStraysFromFolia() {
        // The trap: CAS drifted from Folia BEFORE the action ran — a gap syncFromNms should
        // have erased. That is a genuine rate divergence (the double-ejector signature), so
        // the dropper must be left to Folia; NOT a benign ordering lead.
        var snaps = DropperPhaseGrader.parse(String.join("\n",
            phaseLine(100, sample(A, "DROPPER", 8, 6, 5)),
            phaseLine(101, sample(A, "DROPPER", 6, 2, 1))));
        var report = DropperPhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals("RATE-DIVERGENCE", report.classification());
        assertEquals(2, report.rateDivergedPositions());
        assertTrue(report.maxObservedPreGap() > 0);
    }

    @Test
    void restingDropperStepsZeroAndIsOrderingWhenPreMatches() {
        // An all-empty dropper does not eject: pre==post==folia, step 0. Not evidence of a
        // rate gap — it must grade PASS (ordering artifact) at tolerance 0.
        var snaps = DropperPhaseGrader.parse(
            phaseLine(100, sample(A, "DROPPER", 0, 0, 0)));
        var report = DropperPhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0, report.minSelfStep());
        assertEquals(0, report.maxSelfStep());
    }

    @Test
    void toleranceAllowsSmallPreGap() {
        // pre off folia by 1: within tolerance 1 (still ordering-ish), beyond tolerance 0.
        var snaps = DropperPhaseGrader.parse(
            phaseLine(100, sample(A, "DROPPER", 8, 7, 6)));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            DropperPhaseGrader.grade(snaps, 1).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            DropperPhaseGrader.grade(snaps, 0).verdict());
    }

    @Test
    void preGapIsMaxAcrossAllDroppers() {
        var snaps = DropperPhaseGrader.parse(
            phaseLine(100,
                sample(A, "DROPPER", 20, 23, 22),      // pre gap 3
                sample(B, "DISPENSER", 50, 43, 42)));   // pre gap 7
        var report = DropperPhaseGrader.grade(snaps, 2);
        assertEquals(7, report.maxObservedPreGap());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(2, report.rateDivergedPositions());  // both exceed tolerance 2
    }

    @Test
    void meanPreGapAveragedOverAllSampledDroppers() {
        var snaps = DropperPhaseGrader.parse(
            phaseLine(100,
                sample(A, "DROPPER", 12, 10, 9),    // pre gap 2
                sample(B, "DISPENSER", 0, 4, 3)));   // pre gap 4
        var report = DropperPhaseGrader.grade(snaps, 100);
        assertEquals(3.0, report.meanPreGap(), 1e-9);  // (2+4)/2
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
    }

    @Test
    void ignoresOtherMarkerLines() {
        // A single log may carry BE-DROPPER-SLOT, BE-FURNACE-PHASE, BE-FURNACE-TIMER,
        // BE-SETTLED and SETTLED-DIAG lines; none carries the BE-DROPPER-PHASE marker.
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            phaseLine(1, sample(A, "DROPPER", 8, 8, 7)),
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-DROPPER-SLOT: tick=1 tracked=1 "
                + "positions=[DROPPER WorldPos[dimensionId=0, x=100, y=64, z=100] nebula=7 folia=8]",
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-PHASE: tick=1 tracked=1 "
                + "positions=[FURNACE WorldPos[dimensionId=0, x=8, y=-49, z=8] foliaFuel=1332 "
                + "foliaCook=42 preFuel=1332 preCook=42 postFuel=1331 postCook=43]",
            phaseLine(2, sample(A, "DROPPER", 7, 7, 6)));
        var parsed = DropperPhaseGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void inconclusiveWhenNoDroppersSampled() {
        var snaps = DropperPhaseGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-DROPPER-PHASE: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = DropperPhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals("NO-SAMPLES", report.classification());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
        // Step range collapses to 0 when nothing sampled (no bogus MAX_VALUE leak).
        assertEquals(0, report.minSelfStep());
        assertEquals(0, report.maxSelfStep());
    }

    @Test
    void parsesNegativeCoordinatesAndCounts() {
        String line = phaseLine(5, sample(C, "DROPPER", 0, 0, 0));
        var parsed = DropperPhaseGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
    }

    @Test
    void formatterRoundTripsThroughParser() {
        // Anti-drift guard: the formatter's output, fed straight back through the real parser,
        // must reconstruct the samples exactly. A format change breaking the regex fails HERE,
        // not on a wasted live Folia run.
        List<DropperPhaseGrader.DropperPhaseSample> samples = List.of(
            sample(A, "DROPPER", 8, 8, 7),
            sample(B, "DISPENSER", 10, 6, 5),
            sample(C, "DROPPER", 0, 0, 0));
        String body = DropperPhaseFormatter.format(777, samples);
        var parsed = DropperPhaseGrader.parse(body);
        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(777, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(samples, snap.positions());
    }

    @Test
    void rejectsInvalidArguments() {
        List<DropperPhaseGrader.DropperPhaseSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> DropperPhaseGrader.grade(empty, -1));
        assertThrows(IllegalArgumentException.class,
            () -> DropperPhaseGrader.grade(null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> DropperPhaseGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> DropperPhaseGrader.parse((List<String>) null));
    }

    @Test
    void orderingArtifactDoesNotJustifyEjectWriteBack() {
        // The honesty guard for B8 C3: an ORDERING-ARTIFACT PASS rules out a rate bug but does
        // NOT make an eject write-back useful — the dropper inherits the furnace-timer's
        // do-not-arm conclusion. This pins the arithmetic so a future cycle cannot re-read the
        // PASS as "safe to arm" (the previous cycle's Next: pointer) without this test failing.
        //
        // Live-proven invariant (f5b5f9e): on a pulsing dropper foliaSelf==preSelf==N and
        // postSelf==N-1 — Folia already ejected this tick, so the tile authoritatively holds N,
        // and syncFromNms rebased CAS onto it (preSelf==N). Grade confirms pure ordering:
        var snaps = DropperPhaseGrader.parse(String.join("\n",
            phaseLine(100, sample(A, "DROPPER", 8, 8, 7)),
            phaseLine(101, sample(A, "DROPPER", 7, 7, 6))));
        var report = DropperPhaseGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict(), "pure ordering");
        assertEquals(0, report.maxObservedPreGap(), "pre rebased onto folia exactly");

        // Now walk both write-back options against that invariant. Folia's authoritative tile
        // count after its own eject is foliaSelf == N. There is nothing honest to write:
        for (var snap : snaps) {
            for (var p : snap.positions()) {
                int foliaTileAfterItsOwnEject = p.foliaSelf();   // N — Folia already ejected

                // PRE-sampled write-back would push preSelf onto the tile. preSelf == N, so the
                // tile stays exactly what Folia already holds: a pure no-op mirror, contributes
                // nothing. (preSelf == foliaSelf is the very ordering-artifact PASS condition.)
                int afterPreWriteBack = p.preSelf();
                assertEquals(foliaTileAfterItsOwnEject, afterPreWriteBack,
                    "PRE-sampled write-back is a no-op mirror — it writes N onto a tile Folia "
                        + "already holds at N");

                // POST-sampled write-back would push postSelf == N-1 onto the tile, removing a
                // SECOND item on top of Folia's own eject: the double-ejector divergence.
                int afterPostWriteBack = p.postSelf();
                assertEquals(foliaTileAfterItsOwnEject - 1, afterPostWriteBack,
                    "POST-sampled write-back double-ejects — it writes N-1 onto a tile Folia "
                        + "holds at N");
                assertTrue(afterPostWriteBack < foliaTileAfterItsOwnEject,
                    "POST write-back strictly under-counts vs Folia = divergence");
            }
        }
    }

    @Test
    void multipleDroppersRenderAndParseInOrder() {
        List<DropperPhaseGrader.DropperPhaseSample> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(sample(new WorldPos(0, i, 64, 0), "DROPPER", 9 - i, 9 - i, 8 - i));
        }
        var parsed = DropperPhaseGrader.parse(
            phaseLine(50, specs.toArray(new DropperPhaseGrader.DropperPhaseSample[0])));
        assertEquals(5, parsed.get(0).positions().size());
        assertEquals(specs, parsed.get(0).positions());
    }
}
