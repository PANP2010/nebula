package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DropperSlotGapGraderTest {

    /**
     * Builds a BE-DROPPER-SLOT line via the real {@link DropperSlotFormatter}, so the
     * parser is tested against the byte-exact shape the plugin's dropper-slot emit will
     * produce (this is also the producer/consumer round-trip guard).
     */
    private static String dropperLine(int tick, DropperSlotGapGrader.DropperSlotSample... positions) {
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + DropperSlotFormatter.format(tick, List.of(positions));
    }

    private static DropperSlotGapGrader.DropperSlotSample sample(
            WorldPos p, String type, int nebula, int folia) {
        return new DropperSlotGapGrader.DropperSlotSample(p, type, nebula, folia);
    }

    private static final WorldPos A = new WorldPos(0, 100, 64, 100);
    private static final WorldPos B = new WorldPos(0, 5, 64, -3);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedTypeAndCounts() {
        String line = dropperLine(1234,
            sample(A, "DROPPER", 8, 8),
            sample(B, "DISPENSER", 6, 4));
        var parsed = DropperSlotGapGrader.parse(line);

        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        var p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals("DROPPER", p0.type());
        assertEquals(8, p0.nebula());
        assertEquals(8, p0.folia());
        assertEquals(0, p0.gap());

        var p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals("DISPENSER", p1.type());
        assertEquals(2, p1.gap());
    }

    @Test
    void parsesNegativeCoordinatesAndCounts() {
        String line = dropperLine(5, sample(C, "DROPPER", 0, 0));
        var parsed = DropperSlotGapGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
    }

    @Test
    void ignoresNonDropperSlotLines() {
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            dropperLine(1, sample(A, "DROPPER", 8, 8)),
            // A BE-FURNACE-TIMER line must NOT be picked up by the dropper grader.
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: tick=1 tracked=1 "
                + "positions=[FURNACE WorldPos[dimensionId=0, x=0, y=64, z=0] "
                + "nebulaFuel=1580 foliaFuel=1580 nebulaCook=42 foliaCook=42]",
            dropperLine(2, sample(A, "DROPPER", 7, 7)));
        var parsed = DropperSlotGapGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void doesNotCollideWithOtherGraderLines() {
        // A single log may carry all four graders' lines. None of BE-SETTLED, BE-FURNACE-TIMER
        // or SETTLED-DIAG carries the BE-DROPPER-SLOT marker, so all are filtered out.
        String beSettled = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: "
            + "tick=100 tracked=1 positions=[HOPPER WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebula=15 folia=15]";
        String furnace = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: "
            + "tick=100 tracked=1 positions=[FURNACE WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebulaFuel=1 foliaFuel=1 nebulaCook=1 foliaCook=1]";
        String redstone = "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: "
            + "tick=100 tracked=1 positions=[WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebula=15 folia=15]";
        assertTrue(DropperSlotGapGrader.parse(beSettled).isEmpty());
        assertTrue(DropperSlotGapGrader.parse(furnace).isEmpty());
        assertTrue(DropperSlotGapGrader.parse(redstone).isEmpty());
    }

    @Test
    void passesWhenEveryDropperTracksFoliaExactly() {
        var snaps = DropperSlotGapGrader.parse(String.join("\n",
            dropperLine(100, sample(A, "DROPPER", 8, 8), sample(B, "DISPENSER", 3, 3)),
            dropperLine(200, sample(A, "DROPPER", 7, 7), sample(B, "DISPENSER", 2, 2))));
        var report = DropperSlotGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0, report.maxObservedGap());
        assertEquals(4, report.sampledPositions());
        assertEquals(0, report.divergedPositions());
        assertEquals(2, report.snapshots());
        assertEquals(0.0, report.meanGap(), 1e-9);
    }

    @Test
    void failsOnAnyGapUnderZeroTolerance() {
        // A single-item self-count mismatch is a FAIL at tolerance 0 — the safe-mirror bar.
        // This is the double-ejector signature: the shadow ejected one more than Folia.
        var snaps = DropperSlotGapGrader.parse(
            dropperLine(100, sample(A, "DROPPER", 7, 8)));
        var report = DropperSlotGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(1, report.divergedPositions());
        assertEquals(1, report.maxObservedGap());
    }

    @Test
    void toleranceAllowsSmallGap() {
        // self count off by 1: within tolerance 1 (PASS), beyond tolerance 0 (FAIL).
        var snaps = DropperSlotGapGrader.parse(
            dropperLine(100, sample(A, "DROPPER", 7, 8)));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            DropperSlotGapGrader.grade(snaps, 1).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            DropperSlotGapGrader.grade(snaps, 0).verdict());
    }

    @Test
    void worstGapIsMaxAcrossAllDroppers() {
        // gap 2 at A, gap 7 at B → worst observed is 7; a double-ejector would show a
        // large, growing gap exactly like this.
        var snaps = DropperSlotGapGrader.parse(
            dropperLine(100, sample(A, "DROPPER", 8, 10), sample(B, "DISPENSER", 20, 27)));
        var report = DropperSlotGapGrader.grade(snaps, 2);
        assertEquals(7, report.maxObservedGap());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(1, report.divergedPositions());  // only B exceeds tolerance 2
    }

    @Test
    void meanGapAveragedOverAllSampledDroppers() {
        var snaps = DropperSlotGapGrader.parse(
            dropperLine(100, sample(A, "DROPPER", 10, 12), sample(B, "DISPENSER", 4, 0)));
        var report = DropperSlotGapGrader.grade(snaps, 100);
        // gaps 2,4 → mean 3.0
        assertEquals(3.0, report.meanGap(), 1e-9);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
    }

    @Test
    void inconclusiveWhenNoDroppersSampled() {
        var snaps = DropperSlotGapGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-DROPPER-SLOT: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = DropperSlotGapGrader.grade(snaps, 0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
    }

    @Test
    void formatterRoundTripsThroughParser() {
        // The load-bearing anti-drift guard: the formatter's output, fed straight back
        // through the real parser, must reconstruct the samples exactly. A format change
        // that breaks the regex fails HERE, not on a wasted live Folia run.
        List<DropperSlotGapGrader.DropperSlotSample> samples = List.of(
            sample(A, "DROPPER", 8, 8),
            sample(B, "DISPENSER", 6, 4),
            sample(C, "DROPPER", 0, 0));
        String body = DropperSlotFormatter.format(777, samples);
        var parsed = DropperSlotGapGrader.parse(body);
        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(777, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(samples, snap.positions());
    }

    @Test
    void rejectsInvalidArguments() {
        List<DropperSlotGapGrader.DropperSlotSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> DropperSlotGapGrader.grade(empty, -1));
        assertThrows(IllegalArgumentException.class,
            () -> DropperSlotGapGrader.grade(null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> DropperSlotGapGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> DropperSlotGapGrader.parse((List<String>) null));
    }

    @Test
    void emptyLogParsesToNothingAndGradesInconclusive() {
        var parsed = DropperSlotGapGrader.parse(List.of());
        assertTrue(parsed.isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            DropperSlotGapGrader.grade(parsed, 0).verdict());
    }

    @Test
    void multipleDroppersRenderAndParseInOrder() {
        List<DropperSlotGapGrader.DropperSlotSample> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(sample(new WorldPos(0, i, 64, 0), "DROPPER", 9 - i, 9 - i));
        }
        var parsed = DropperSlotGapGrader.parse(
            dropperLine(50, specs.toArray(new DropperSlotGapGrader.DropperSlotSample[0])));
        assertEquals(5, parsed.get(0).positions().size());
        assertEquals(specs, parsed.get(0).positions());
    }
}
