package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoliaDivergenceGraderTest {

    /**
     * Builds a CASCADE-DIAG line byte-identically to how NebulaPlugin emits it, so
     * the parser is tested against the real format (including the '→' arrow and the
     * commas inside the WorldPos record rendering).
     */
    private static String diagLine(int microSteps, int modified, SeedSpec... seeds) {
        List<String> seedStrs = new ArrayList<>();
        for (SeedSpec s : seeds) {
            WorldPos p = s.pos;
            String rendered = "WorldPos[dimensionId=" + p.dimensionId() + ", x=" + p.x()
                + ", y=" + p.y() + ", z=" + p.z() + "]";
            seedStrs.add(rendered + " cas=" + s.cas + "→nms=" + s.nms
                + (s.cas == s.nms ? " (settled)" : " (dirty)"));
        }
        // Mirror NebulaPlugin: seedTasks == seed count, seeds=[...] via List.toString().
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] CASCADE-DIAG: seedTasks="
            + seeds.length + " microsteps=" + microSteps + " modified=" + modified
            + " seeds=" + seedStrs;
    }

    private record SeedSpec(WorldPos pos, int cas, int nms) {}

    private static SeedSpec settled(WorldPos p, int v) {
        return new SeedSpec(p, v, v);
    }

    private static SeedSpec dirty(WorldPos p, int cas, int nms) {
        return new SeedSpec(p, cas, nms);
    }

    private static final WorldPos A = new WorldPos(0, 1, -60, 2);
    private static final WorldPos B = new WorldPos(0, 17, -60, 2);

    @Test
    void parsesSeedTasksMicrostepsModifiedAndSeeds() {
        String line = diagLine(14, 15, dirty(A, 0, 15), settled(B, 3));
        List<FoliaDivergenceGrader.DiagInvocation> parsed = FoliaDivergenceGrader.parse(line);

        assertEquals(1, parsed.size());
        FoliaDivergenceGrader.DiagInvocation inv = parsed.get(0);
        assertEquals(2, inv.seedTasks());
        assertEquals(14, inv.microSteps());
        assertEquals(15, inv.modified());
        assertEquals(2, inv.seeds().size());

        FoliaDivergenceGrader.SeedSample s0 = inv.seeds().get(0);
        assertEquals(A, s0.pos());
        assertEquals(0, s0.casBefore());
        assertEquals(15, s0.nmsAfter());
        assertTrue(s0.dirty());

        FoliaDivergenceGrader.SeedSample s1 = inv.seeds().get(1);
        assertEquals(B, s1.pos());
        assertFalse(s1.dirty());
    }

    @Test
    void parsesNegativeCoordinatesAndPowerLevels() {
        WorldPos deep = new WorldPos(0, -100, -59, -33);
        String line = diagLine(1, 1, settled(deep, 0));
        var parsed = FoliaDivergenceGrader.parse(line);
        assertEquals(deep, parsed.get(0).seeds().get(0).pos());
    }

    @Test
    void ignoresNonDiagLines() {
        String log = String.join("\n",
            "[12:00:00] [Server] Starting Folia",
            diagLine(1, 1, settled(A, 0)),
            "[12:00:01] [Server] Some other line",
            diagLine(2, 3, dirty(A, 0, 15)));
        var parsed = FoliaDivergenceGrader.parse(log);
        assertEquals(2, parsed.size());
    }

    @Test
    void gradePassesWhenResidualDirtyRateIsLow() {
        List<FoliaDivergenceGrader.DiagInvocation> invs = new ArrayList<>();
        // 4 cold-transient invocations (all dirty) then 16 settled ones.
        for (int i = 0; i < 4; i++) {
            invs.addAll(FoliaDivergenceGrader.parse(diagLine(1, 1, dirty(A, 0, 15))));
        }
        for (int i = 0; i < 16; i++) {
            invs.addAll(FoliaDivergenceGrader.parse(diagLine(0, 0, settled(A, 15))));
        }
        var report = FoliaDivergenceGrader.grade(invs, 4, 0.05);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, report.verdict());
        assertEquals(0.0, report.dirtyRate());
        assertEquals(16, report.gradedInvocations());
        assertEquals(16, report.gradedSamples());
        assertEquals(0, report.dirtySamples());
    }

    @Test
    void gradeFailsOnSustainedPostTransientDivergence() {
        List<FoliaDivergenceGrader.DiagInvocation> invs = new ArrayList<>();
        // Every graded invocation stays dirty — a real divergence signal, not a transient.
        for (int i = 0; i < 20; i++) {
            invs.addAll(FoliaDivergenceGrader.parse(diagLine(1, 1, dirty(A, 5, 15))));
        }
        var report = FoliaDivergenceGrader.grade(invs, 4, 0.05);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, report.verdict());
        assertEquals(1.0, report.dirtyRate());
    }

    @Test
    void transientDirtSuppressedByConvergeWindowDoesNotFail() {
        List<FoliaDivergenceGrader.DiagInvocation> invs = new ArrayList<>();
        // 6 dirty (cold transient) then 14 settled; window 6 excludes the transient.
        for (int i = 0; i < 6; i++) {
            invs.addAll(FoliaDivergenceGrader.parse(diagLine(1, 1, dirty(A, 0, 15))));
        }
        for (int i = 0; i < 14; i++) {
            invs.addAll(FoliaDivergenceGrader.parse(diagLine(0, 0, settled(A, 15))));
        }
        var windowed = FoliaDivergenceGrader.grade(invs, 6, 0.05);
        assertEquals(FoliaDivergenceGrader.Verdict.PASS, windowed.verdict());

        // With no window, the same log fails: the transient dirt is counted.
        var noWindow = FoliaDivergenceGrader.grade(invs, 0, 0.05);
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL, noWindow.verdict());
        assertEquals(6.0 / 20.0, noWindow.dirtyRate(), 1e-9);
    }

    @Test
    void inconclusiveWhenNoGradedSamplesRemain() {
        var invs = FoliaDivergenceGrader.parse(diagLine(1, 1, dirty(A, 0, 15)));
        // convergeWindow >= invocation count leaves nothing to grade.
        var report = FoliaDivergenceGrader.grade(invs, 5, 0.05);
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE, report.verdict());
        assertEquals(0, report.gradedSamples());
        assertFalse(report.passed());
    }

    @Test
    void multipleSeedsPerInvocationAllCounted() {
        var invs = FoliaDivergenceGrader.parse(
            diagLine(3, 2, settled(A, 0), dirty(B, 0, 15)));
        var report = FoliaDivergenceGrader.grade(invs, 0, 1.0);
        assertEquals(2, report.gradedSamples());
        assertEquals(1, report.dirtySamples());
        assertEquals(0.5, report.dirtyRate(), 1e-9);
    }

    @Test
    void rejectsInvalidArguments() {
        List<FoliaDivergenceGrader.DiagInvocation> empty = List.of();
        assertThrows(IllegalArgumentException.class,
            () -> FoliaDivergenceGrader.grade(empty, -1, 0.05));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaDivergenceGrader.grade(empty, 0, 1.5));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaDivergenceGrader.grade(null, 0, 0.05));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaDivergenceGrader.parse((String) null));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaDivergenceGrader.parse((List<String>) null));
    }

    @Test
    void emptyLogParsesToNothingAndGradesInconclusive() {
        var parsed = FoliaDivergenceGrader.parse(List.of());
        assertTrue(parsed.isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            FoliaDivergenceGrader.grade(parsed, 0, 0.05).verdict());
    }
}
