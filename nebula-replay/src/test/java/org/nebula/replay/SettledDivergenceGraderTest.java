package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettledDivergenceGraderTest {

    /**
     * Builds a SETTLED-DIAG line in the format the plugin's quiescence snapshot will
     * emit, so the parser is tested against the real shape (including the '[', ']'
     * and the commas inside the WorldPos record rendering).
     */
    private static String settledLine(int tick, PosSpec... positions) {
        List<String> parts = new ArrayList<>();
        for (PosSpec s : positions) {
            WorldPos p = s.pos;
            String rendered = "WorldPos[dimensionId=" + p.dimensionId() + ", x=" + p.x()
                + ", y=" + p.y() + ", z=" + p.z() + "]";
            parts.add(rendered + " nebula=" + s.nebula + " folia=" + s.folia);
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: tick=" + tick
            + " tracked=" + positions.length + " positions=" + parts;
    }

    private record PosSpec(WorldPos pos, int nebula, int folia) {}

    private static PosSpec agree(WorldPos p, int v) {
        return new PosSpec(p, v, v);
    }

    private static PosSpec diverge(WorldPos p, int nebula, int folia) {
        return new PosSpec(p, nebula, folia);
    }

    private static final WorldPos A = new WorldPos(0, 1, -60, 2);
    private static final WorldPos B = new WorldPos(0, 17, -60, 2);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedAndPositions() {
        String line = settledLine(1234, agree(A, 15), diverge(B, 0, 14));
        List<SettledDivergenceGrader.SettledSnapshot> parsed = SettledDivergenceGrader.parse(line);

        assertEquals(1, parsed.size());
        SettledDivergenceGrader.SettledSnapshot snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        SettledDivergenceGrader.PositionSample p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals(15, p0.nebula());
        assertEquals(15, p0.folia());
        assertFalse(p0.diverged());

        SettledDivergenceGrader.PositionSample p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals(0, p1.nebula());
        assertEquals(14, p1.folia());
        assertTrue(p1.diverged());
    }

    @Test
    void parsesNegativeCoordinatesAndPowerLevels() {
        String line = settledLine(5, agree(C, 0));
        var parsed = SettledDivergenceGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
    }

    @Test
    void ignoresNonSettledLines() {
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            settledLine(1, agree(A, 15)),
            // A CASCADE-DIAG line must NOT be picked up by this grader.
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] CASCADE-DIAG: seedTasks=1 microsteps=1"
                + " modified=1 seeds=[WorldPos[dimensionId=0, x=1, y=-60, z=2] cas=0→nms=15 (dirty)]",
            settledLine(2, diverge(A, 0, 15)));
        var parsed = SettledDivergenceGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void passesWhenEveryPositionAgreesAtQuiescence() {
        // Two snapshots, all positions in exact agreement — the correct settled state.
        var snaps = SettledDivergenceGrader.parse(String.join("\n",
            settledLine(100, agree(A, 15), agree(B, 15)),
            settledLine(200, agree(A, 0), agree(B, 0))));
        var report = SettledDivergenceGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0.0, report.divergenceRate());
        assertEquals(4, report.sampledPositions());
        assertEquals(0, report.divergedPositions());
        assertEquals(2, report.snapshots());
    }

    @Test
    void failsOnAnyDivergenceUnderZeroThreshold() {
        // At quiescence there is no observe-lag excuse: a single mismatch is a real
        // Folia-vs-Nebula divergence and must FAIL under the honest default (0.0).
        var snaps = SettledDivergenceGrader.parse(
            settledLine(100, agree(A, 15), diverge(B, 15, 0)));
        var report = SettledDivergenceGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(0.5, report.divergenceRate(), 1e-9);
        assertEquals(1, report.divergedPositions());
    }

    @Test
    void toleranceThresholdAllowsSmallDivergence() {
        // 1 diverged of 10 = 0.1; passes at 0.15, fails at 0.05.
        List<PosSpec> specs = new ArrayList<>();
        specs.add(diverge(A, 0, 1));
        for (int i = 0; i < 9; i++) {
            specs.add(agree(new WorldPos(0, i, -60, 0), 15));
        }
        var snaps = SettledDivergenceGrader.parse(
            settledLine(100, specs.toArray(new PosSpec[0])));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            SettledDivergenceGrader.grade(snaps, 0.15).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            SettledDivergenceGrader.grade(snaps, 0.05).verdict());
    }

    @Test
    void inconclusiveWhenNoPositionsSampled() {
        // A SETTLED-DIAG line with an empty positions list (tracked=0) grades inconclusive.
        var snaps = SettledDivergenceGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = SettledDivergenceGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
    }

    @Test
    void divergenceAcrossMultipleSnapshotsIsAggregated() {
        // First snapshot clean, second has a lingering divergence — aggregate is 1/4.
        var snaps = SettledDivergenceGrader.parse(String.join("\n",
            settledLine(100, agree(A, 15), agree(B, 15)),
            settledLine(200, agree(A, 15), diverge(B, 15, 0))));
        var report = SettledDivergenceGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(4, report.sampledPositions());
        assertEquals(1, report.divergedPositions());
        assertEquals(0.25, report.divergenceRate(), 1e-9);
    }

    @Test
    void rejectsInvalidArguments() {
        List<SettledDivergenceGrader.SettledSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> SettledDivergenceGrader.grade(empty, -0.1));
        assertThrows(IllegalArgumentException.class,
            () -> SettledDivergenceGrader.grade(empty, 1.5));
        assertThrows(IllegalArgumentException.class,
            () -> SettledDivergenceGrader.grade(null, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> SettledDivergenceGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> SettledDivergenceGrader.parse((List<String>) null));
    }

    @Test
    void emptyLogParsesToNothingAndGradesInconclusive() {
        var parsed = SettledDivergenceGrader.parse(List.of());
        assertTrue(parsed.isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            SettledDivergenceGrader.grade(parsed, 0.0).verdict());
    }
}
