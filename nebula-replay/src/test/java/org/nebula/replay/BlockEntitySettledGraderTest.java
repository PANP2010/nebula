package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntitySettledGraderTest {

    /**
     * Builds a BE-SETTLED line via the real {@link BlockEntitySettledFormatter}, so the
     * parser is tested against the byte-exact shape the plugin's quiescence snapshot
     * will emit (this is also the producer/consumer round-trip guard).
     */
    private static String settledLine(int tick, PosSpec... positions) {
        List<BlockEntitySettledGrader.BlockEntitySample> samples = new ArrayList<>();
        for (PosSpec s : positions) {
            samples.add(new BlockEntitySettledGrader.BlockEntitySample(s.pos, s.type, s.nebula, s.folia));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + BlockEntitySettledFormatter.format(tick, samples);
    }

    private record PosSpec(WorldPos pos, String type, int nebula, int folia) {}

    private static PosSpec agree(WorldPos p, String type, int v) {
        return new PosSpec(p, type, v, v);
    }

    private static PosSpec diverge(WorldPos p, String type, int nebula, int folia) {
        return new PosSpec(p, type, nebula, folia);
    }

    private static final WorldPos A = new WorldPos(0, 0, 64, 0);
    private static final WorldPos B = new WorldPos(0, 5, 64, -3);
    private static final WorldPos C = new WorldPos(0, -100, -59, -33);

    @Test
    void parsesTickTrackedTypeAndPositions() {
        String line = settledLine(1234, agree(A, "HOPPER", 48), diverge(B, "FURNACE", 0, 64));
        var parsed = BlockEntitySettledGrader.parse(line);

        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(2, snap.tracked());
        assertEquals(2, snap.positions().size());

        var p0 = snap.positions().get(0);
        assertEquals(A, p0.pos());
        assertEquals("HOPPER", p0.type());
        assertEquals(48, p0.nebula());
        assertEquals(48, p0.folia());
        assertFalse(p0.diverged());

        var p1 = snap.positions().get(1);
        assertEquals(B, p1.pos());
        assertEquals("FURNACE", p1.type());
        assertEquals(0, p1.nebula());
        assertEquals(64, p1.folia());
        assertTrue(p1.diverged());
    }

    @Test
    void parsesNegativeCoordinatesAndCounts() {
        String line = settledLine(5, agree(C, "HOPPER", 0));
        var parsed = BlockEntitySettledGrader.parse(line);
        assertEquals(C, parsed.get(0).positions().get(0).pos());
        assertEquals("HOPPER", parsed.get(0).positions().get(0).type());
    }

    @Test
    void ignoresNonBlockEntitySettledLines() {
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            settledLine(1, agree(A, "HOPPER", 48)),
            // A BE-CAS-DIAG line (the per-tick delta stream) must NOT be picked up.
            "[12:00:01] [org.nebula.plugin.NebulaPlugin] BE-CAS-DIAG: HOPPER "
                + "WorldPos[dimensionId=0, x=0, y=64, z=0] cooldown=7 self-slots 48→48 "
                + "above(cross-region/absent) slot0 0→0 output(cross-region/absent) slot0 64→64 [no-change]",
            settledLine(2, diverge(A, "HOPPER", 0, 48)));
        var parsed = BlockEntitySettledGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void doesNotCollideWithRedstoneSettledDiagLine() {
        // A single log may carry BOTH graders' lines. The redstone SETTLED-DIAG format
        // starts each position with WorldPos[...] (no leading type token), so this
        // grader's "(\w+) WorldPos" pattern must NOT match it — otherwise the two
        // graders would cross-contaminate on a combined server-run.log.
        String redstone = "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: "
            + "tick=100 tracked=1 positions=[WorldPos[dimensionId=0, x=1, y=-60, z=2] "
            + "nebula=15 folia=15]";
        var parsed = BlockEntitySettledGrader.parse(redstone);
        // The line has no BE-SETTLED marker, so it is filtered out entirely.
        assertTrue(parsed.isEmpty());
    }

    @Test
    void passesWhenEveryPositionAgreesAtQuiescence() {
        var snaps = BlockEntitySettledGrader.parse(String.join("\n",
            settledLine(100, agree(A, "HOPPER", 48), agree(B, "FURNACE", 0)),
            settledLine(200, agree(A, "HOPPER", 12), agree(B, "FURNACE", 0))));
        var report = BlockEntitySettledGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0.0, report.divergenceRate());
        assertEquals(4, report.sampledPositions());
        assertEquals(0, report.divergedPositions());
        assertEquals(2, report.snapshots());
    }

    @Test
    void failsOnAnyDivergenceUnderZeroThreshold() {
        var snaps = BlockEntitySettledGrader.parse(
            settledLine(100, agree(A, "HOPPER", 48), diverge(B, "HOPPER", 48, 0)));
        var report = BlockEntitySettledGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(0.5, report.divergenceRate(), 1e-9);
        assertEquals(1, report.divergedPositions());
    }

    @Test
    void toleranceThresholdAllowsSmallDivergence() {
        // 1 diverged of 10 = 0.1; passes at 0.15, fails at 0.05.
        List<PosSpec> specs = new ArrayList<>();
        specs.add(diverge(A, "HOPPER", 0, 1));
        for (int i = 0; i < 9; i++) {
            specs.add(agree(new WorldPos(0, i, 64, 0), "HOPPER", 48));
        }
        var snaps = BlockEntitySettledGrader.parse(
            settledLine(100, specs.toArray(new PosSpec[0])));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            BlockEntitySettledGrader.grade(snaps, 0.15).verdict());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            BlockEntitySettledGrader.grade(snaps, 0.05).verdict());
    }

    @Test
    void inconclusiveWhenNoPositionsSampled() {
        var snaps = BlockEntitySettledGrader.parse(
            "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: tick=1 tracked=0 positions=[]");
        assertEquals(1, snaps.size());
        var report = BlockEntitySettledGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals(0, report.sampledPositions());
        assertFalse(report.passed());
    }

    @Test
    void divergenceAcrossMultipleSnapshotsIsAggregated() {
        var snaps = BlockEntitySettledGrader.parse(String.join("\n",
            settledLine(100, agree(A, "HOPPER", 48), agree(B, "HOPPER", 48)),
            settledLine(200, agree(A, "HOPPER", 48), diverge(B, "HOPPER", 48, 0))));
        var report = BlockEntitySettledGrader.grade(snaps, 0.0);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(4, report.sampledPositions());
        assertEquals(1, report.divergedPositions());
        assertEquals(0.25, report.divergenceRate(), 1e-9);
    }

    @Test
    void formatterRoundTripsThroughParser() {
        // The load-bearing anti-drift guard: the formatter's output, fed straight back
        // through the real parser, must reconstruct the samples exactly. A format change
        // that breaks the regex fails HERE, not on a wasted live Folia run.
        List<BlockEntitySettledGrader.BlockEntitySample> samples = List.of(
            new BlockEntitySettledGrader.BlockEntitySample(A, "HOPPER", 48, 48),
            new BlockEntitySettledGrader.BlockEntitySample(B, "FURNACE", 3, 5),
            new BlockEntitySettledGrader.BlockEntitySample(C, "DISPENSER", 0, 0));
        String body = BlockEntitySettledFormatter.format(777, samples);
        var parsed = BlockEntitySettledGrader.parse(body);
        assertEquals(1, parsed.size());
        var snap = parsed.get(0);
        assertEquals(777, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(samples, snap.positions());
    }

    @Test
    void rejectsInvalidArguments() {
        List<BlockEntitySettledGrader.BlockEntitySettledSnapshot> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> BlockEntitySettledGrader.grade(empty, -0.1));
        assertThrows(IllegalArgumentException.class,
            () -> BlockEntitySettledGrader.grade(empty, 1.5));
        assertThrows(IllegalArgumentException.class,
            () -> BlockEntitySettledGrader.grade(null, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> BlockEntitySettledGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> BlockEntitySettledGrader.parse((List<String>) null));
    }

    @Test
    void emptyLogParsesToNothingAndGradesInconclusive() {
        var parsed = BlockEntitySettledGrader.parse(List.of());
        assertTrue(parsed.isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            BlockEntitySettledGrader.grade(parsed, 0.0).verdict());
    }
}
