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
 * Tests the thin CLI's I/O and exit-code mapping. The grading itself is covered by
 * {@link FurnaceTimerGapGraderTest}; here we only assert the CLI delegates and maps
 * verdicts to the documented exit codes, against the byte-exact BE-FURNACE-TIMER log
 * format the plugin will emit (built via the real {@link FurnaceTimerFormatter}).
 */
class FurnaceTimerGapGraderCliTest {

    /**
     * A BE-FURNACE-TIMER line snapshotting {@code count} furnaces where the first
     * {@code divergedCount} of them have a cook_progress gap of {@code gap} ticks.
     */
    private static String timerLine(int tick, int count, int divergedCount, int gap) {
        List<FurnaceTimerGapGrader.FurnaceTimerSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorldPos p = new WorldPos(0, i, 64, 2);
            int foliaCook = 42;
            int nebulaCook = i < divergedCount ? foliaCook + gap : foliaCook;
            samples.add(new FurnaceTimerGapGrader.FurnaceTimerSample(p, 1580, 1580, nebulaCook, foliaCook));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + FurnaceTimerFormatter.format(tick, samples);
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = FurnaceTimerGapGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("be-furnace-timer-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passWhenAllFurnacesTrackFoliaExactly() throws IOException {
        Path log = writeLog(List.of(timerLine(100, 8, 0, 0)));
        Run r = invoke(log.toString());       // default tolerance 0
        assertEquals(FurnaceTimerGapGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Furnaces sampled:     8"), r.out());
        assertTrue(r.out().contains("Diverged furnaces:    0"), r.out());
    }

    @Test
    void failOnAnyGapWithDefaultTolerance() throws IOException {
        Path log = writeLog(List.of(timerLine(100, 8, 1, 3)));
        Run r = invoke(log.toString());
        assertEquals(FurnaceTimerGapGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Diverged furnaces:    1"), r.out());
        assertTrue(r.out().contains("Max cook_progress gap:3 ticks"), r.out());
    }

    @Test
    void toleranceAllowsSmallGap() throws IOException {
        // gap of 2 ticks on 1 furnace; tolerance 2 must PASS (gap not > tolerance).
        Path log = writeLog(List.of(timerLine(100, 4, 1, 2)));
        Run r = invoke(log.toString(), "2");
        assertEquals(FurnaceTimerGapGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(timerLine(100, 10, 0, 0));
        lines.add(timerLine(200, 10, 1, 5));
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0");
        assertEquals(FurnaceTimerGapGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Timer snapshots:      2"), r.out());
        assertTrue(r.out().contains("Furnaces sampled:     20"), r.out());
    }

    @Test
    void inconclusiveWhenNoTimerLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(FurnaceTimerGapGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresBeSettledLines() throws IOException {
        // A BE-SETTLED (hopper inventory) line must NOT be picked up by the timer grader.
        String beSettled = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-SETTLED: tick=1 "
            + "tracked=1 positions=[HOPPER WorldPos[dimensionId=0, x=0, y=64, z=0] nebula=48 folia=48]";
        Path log = writeLog(List.of(beSettled));
        Run r = invoke(log.toString());
        assertEquals(FurnaceTimerGapGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(FurnaceTimerGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(FurnaceTimerGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonIntegerTolerance() throws IOException {
        Path log = writeLog(List.of(timerLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "notanint");
        assertEquals(FurnaceTimerGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceTicks must be an integer"), r.err());
    }

    @Test
    void usageErrorOnNegativeTolerance() throws IOException {
        Path log = writeLog(List.of(timerLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "-1");
        assertEquals(FurnaceTimerGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceTicks must be >= 0"), r.err());
    }
}
