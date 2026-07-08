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
 * {@link SettledDivergenceGraderTest}; here we only assert the CLI delegates and maps
 * verdicts to the documented exit codes, byte-identical to the real SETTLED-DIAG
 * log format the plugin will emit.
 */
class SettledDivergenceGraderCliTest {

    /** Renders one WorldPos exactly as the record's toString produces it. */
    private static String renderPos(WorldPos p) {
        return "WorldPos[dimensionId=" + p.dimensionId() + ", x=" + p.x()
            + ", y=" + p.y() + ", z=" + p.z() + "]";
    }

    /**
     * A SETTLED-DIAG line snapshotting {@code count} tracked positions where the first
     * {@code divergedCount} of them have nebula != folia.
     */
    private static String settledLine(int tick, int count, int divergedCount) {
        StringBuilder positions = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                positions.append(", ");
            }
            WorldPos p = new WorldPos(0, i, -60, 2);
            int folia = 15;
            int nebula = i < divergedCount ? 0 : 15;
            positions.append(renderPos(p)).append(" nebula=").append(nebula)
                .append(" folia=").append(folia);
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] SETTLED-DIAG: tick=" + tick
            + " tracked=" + count + " positions=[" + positions + "]";
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = SettledDivergenceGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("settled-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passWhenAllPositionsAgreeAtQuiescence() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(settledLine(100, 15, 0));   // every position nebula==folia
        Path log = writeLog(lines);
        Run r = invoke(log.toString());       // default threshold 0.0
        assertEquals(SettledDivergenceGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Positions sampled:   15"), r.out());
        assertTrue(r.out().contains("Diverged positions:  0"), r.out());
    }

    @Test
    void failOnAnyDivergenceWithDefaultThreshold() throws IOException {
        // Default threshold is 0.0: a single diverged position at quiescence must FAIL.
        Path log = writeLog(List.of(settledLine(100, 15, 1)));
        Run r = invoke(log.toString());
        assertEquals(SettledDivergenceGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Diverged positions:  1"), r.out());
    }

    @Test
    void toleranceThresholdAllowsSmallDivergence() throws IOException {
        // 1 of 20 diverged = 0.05; a 0.1 threshold must PASS.
        Path log = writeLog(List.of(settledLine(100, 20, 1)));
        Run r = invoke(log.toString(), "0.1");
        assertEquals(SettledDivergenceGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(settledLine(100, 10, 0));
        lines.add(settledLine(200, 10, 1));   // 1 diverged out of 20 total
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0.0");
        assertEquals(SettledDivergenceGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Settled snapshots:   2"), r.out());
        assertTrue(r.out().contains("Positions sampled:   20"), r.out());
    }

    @Test
    void inconclusiveWhenNoSettledLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(SettledDivergenceGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresCascadeDiagLines() throws IOException {
        // A CASCADE-DIAG line (the residual-dirty stream) must NOT be picked up here.
        String cascade = "[12:00:00] [org.nebula.plugin.NebulaPlugin] CASCADE-DIAG: "
            + "seedTasks=1 microsteps=1 modified=1 seeds=["
            + renderPos(new WorldPos(0, 1, -60, 2)) + " cas=0→nms=15 (dirty)]";
        Path log = writeLog(List.of(cascade));
        Run r = invoke(log.toString());
        assertEquals(SettledDivergenceGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(SettledDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(SettledDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonNumericThreshold() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 1, 0)));
        Run r = invoke(log.toString(), "notadouble");
        assertEquals(SettledDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("maxDivergenceRate must be a double"), r.err());
    }

    @Test
    void usageErrorOnOutOfRangeThreshold() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 1, 0)));
        Run r = invoke(log.toString(), "1.5");
        assertEquals(SettledDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("maxDivergenceRate"), r.err());
    }
}
