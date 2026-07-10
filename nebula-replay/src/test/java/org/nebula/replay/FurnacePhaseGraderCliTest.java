package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the thin CLI's I/O and exit-code mapping. The classification itself is covered by
 * {@link FurnacePhaseGraderTest}; here we only assert the CLI delegates and maps verdicts to
 * the documented exit codes, against the byte-exact BE-FURNACE-PHASE log format the plugin
 * emits (built via the real {@link FurnacePhaseFormatter}).
 */
class FurnacePhaseGraderCliTest {

    /**
     * A BE-FURNACE-PHASE line snapshotting {@code count} furnaces where the first
     * {@code divergedCount} of them stray from Folia PRE-action by {@code preGap} ticks (a
     * rate divergence); the rest are pure ordering artifacts (pre==folia, action steps +1/-1).
     */
    private static String phaseLine(int tick, int count, int divergedCount, int preGap) {
        List<FurnacePhaseGrader.FurnacePhaseSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorldPos p = new WorldPos(0, i, -49, 2);
            int foliaFuel = 1332;
            int foliaCook = 42;
            int preCook = i < divergedCount ? foliaCook + preGap : foliaCook;
            int preFuel = foliaFuel;  // divergence isolated to the cook axis
            samples.add(new FurnacePhaseGrader.FurnacePhaseSample(
                p, foliaFuel, foliaCook, preFuel, preCook, preFuel - 1, preCook + 1));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + FurnacePhaseFormatter.format(tick, samples);
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = FurnacePhaseGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("be-furnace-phase-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passOrderingArtifactWhenPreMatchesFolia() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 8, 0, 0)));
        Run r = invoke(log.toString());       // default tolerance 0
        assertEquals(FurnacePhaseGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Classification: ORDERING-ARTIFACT"), r.out());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Furnaces sampled:     8"), r.out());
        assertTrue(r.out().contains("Rate-diverged:        0"), r.out());
        // Confirm the reported action step is the expected cook +1 / fuel -1.
        assertTrue(r.out().contains("Cook action step:     [1..1]"), r.out());
        assertTrue(r.out().contains("Fuel action step:     [-1..-1]"), r.out());
    }

    @Test
    void failRateDivergenceOnAnyPreGapWithDefaultTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 8, 1, 3)));
        Run r = invoke(log.toString());
        assertEquals(FurnacePhaseGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Classification: RATE-DIVERGENCE"), r.out());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Rate-diverged:        1"), r.out());
        assertTrue(r.out().contains("Max pre cook gap:     3 ticks"), r.out());
    }

    @Test
    void toleranceAllowsSmallPreGap() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 4, 1, 2)));
        Run r = invoke(log.toString(), "2");
        assertEquals(FurnacePhaseGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(phaseLine(100, 10, 0, 0));
        lines.add(phaseLine(200, 10, 1, 5));
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0");
        assertEquals(FurnacePhaseGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Phase snapshots:      2"), r.out());
        assertTrue(r.out().contains("Furnaces sampled:     20"), r.out());
    }

    @Test
    void inconclusiveWhenNoPhaseLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(FurnacePhaseGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Classification: NO-SAMPLES"), r.out());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresBeFurnaceTimerLines() throws IOException {
        // A BE-FURNACE-TIMER line must NOT be picked up by the phase grader (disjoint marker).
        String timer = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: tick=1 "
            + "tracked=1 positions=[FURNACE WorldPos[dimensionId=0, x=8, y=-49, z=8] "
            + "nebulaFuel=1331 foliaFuel=1332 nebulaCook=43 foliaCook=42]";
        Path log = writeLog(List.of(timer));
        Run r = invoke(log.toString());
        assertEquals(FurnacePhaseGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(FurnacePhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(FurnacePhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonIntegerTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "notanint");
        assertEquals(FurnacePhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceTicks must be an integer"), r.err());
    }

    @Test
    void usageErrorOnNegativeTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "-1");
        assertEquals(FurnacePhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceTicks must be >= 0"), r.err());
    }
}
