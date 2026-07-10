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
 * {@link DropperPhaseGraderTest}; here we only assert the CLI delegates and maps verdicts to
 * the documented exit codes, against the byte-exact BE-DROPPER-PHASE log format the plugin
 * will emit (built via the real {@link DropperPhaseFormatter}).
 */
class DropperPhaseGraderCliTest {

    /**
     * A BE-DROPPER-PHASE line snapshotting {@code count} droppers where the first
     * {@code divergedCount} of them have a PRE-action self-count gap of {@code preGap} items
     * (CAS strayed from Folia before the action ran). All droppers step {@code -1} post-action.
     */
    private static String phaseLine(int tick, int count, int divergedCount, int preGap) {
        List<DropperPhaseGrader.DropperPhaseSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorldPos p = new WorldPos(0, i, 64, 2);
            int foliaSelf = 8;
            int preSelf = i < divergedCount ? foliaSelf - preGap : foliaSelf;
            int postSelf = preSelf - 1;  // the action's own eject step
            samples.add(new DropperPhaseGrader.DropperPhaseSample(p, "DROPPER", foliaSelf, preSelf, postSelf));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + DropperPhaseFormatter.format(tick, samples);
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = DropperPhaseGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("be-dropper-phase-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passOrderingArtifactWhenPreMatchesFolia() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 8, 0, 0)));
        Run r = invoke(log.toString());       // default tolerance 0
        assertEquals(DropperPhaseGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Classification: ORDERING-ARTIFACT"), r.out());
        assertTrue(r.out().contains("Droppers sampled:     8"), r.out());
        assertTrue(r.out().contains("Rate-diverged:        0"), r.out());
        // The eject action stepped exactly -1.
        assertTrue(r.out().contains("Self action step:     [-1..-1]"), r.out());
    }

    @Test
    void failRateDivergenceOnAnyPreGapWithDefaultTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 8, 1, 3)));
        Run r = invoke(log.toString());
        assertEquals(DropperPhaseGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Classification: RATE-DIVERGENCE"), r.out());
        assertTrue(r.out().contains("Rate-diverged:        1"), r.out());
        assertTrue(r.out().contains("Max pre self gap:     3 items"), r.out());
    }

    @Test
    void toleranceAllowsSmallPreGap() throws IOException {
        // pre gap of 2 items on 1 dropper; tolerance 2 must PASS (gap not > tolerance).
        Path log = writeLog(List.of(phaseLine(100, 4, 1, 2)));
        Run r = invoke(log.toString(), "2");
        assertEquals(DropperPhaseGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(phaseLine(100, 10, 0, 0));
        lines.add(phaseLine(200, 10, 1, 5));
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0");
        assertEquals(DropperPhaseGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Phase snapshots:      2"), r.out());
        assertTrue(r.out().contains("Droppers sampled:     20"), r.out());
    }

    @Test
    void inconclusiveWhenNoDropperPhaseLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(DropperPhaseGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresDropperSlotLines() throws IOException {
        // A BE-DROPPER-SLOT line must NOT be picked up by the dropper PHASE grader.
        String slot = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-DROPPER-SLOT: tick=1 "
            + "tracked=1 positions=[DROPPER WorldPos[dimensionId=0, x=0, y=64, z=0] "
            + "nebula=7 folia=8]";
        Path log = writeLog(List.of(slot));
        Run r = invoke(log.toString());
        assertEquals(DropperPhaseGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(DropperPhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(DropperPhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonIntegerTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "notanint");
        assertEquals(DropperPhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceItems must be an integer"), r.err());
    }

    @Test
    void usageErrorOnNegativeTolerance() throws IOException {
        Path log = writeLog(List.of(phaseLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "-1");
        assertEquals(DropperPhaseGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceItems must be >= 0"), r.err());
    }
}
