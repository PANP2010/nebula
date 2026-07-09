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
 * {@link BlockEntitySettledGraderTest}; here we only assert the CLI delegates and maps
 * verdicts to the documented exit codes, against the byte-exact BE-SETTLED log format
 * the plugin will emit (built via the real {@link BlockEntitySettledFormatter}).
 */
class BlockEntitySettledGraderCliTest {

    /**
     * A BE-SETTLED line snapshotting {@code count} tracked hoppers where the first
     * {@code divergedCount} of them have nebula != folia.
     */
    private static String settledLine(int tick, int count, int divergedCount) {
        List<BlockEntitySettledGrader.BlockEntitySample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorldPos p = new WorldPos(0, i, 64, 2);
            int folia = 48;
            int nebula = i < divergedCount ? 0 : 48;
            samples.add(new BlockEntitySettledGrader.BlockEntitySample(p, "HOPPER", nebula, folia));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + BlockEntitySettledFormatter.format(tick, samples);
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = BlockEntitySettledGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("be-settled-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passWhenAllPositionsAgreeAtQuiescence() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 8, 0)));
        Run r = invoke(log.toString());       // default threshold 0.0
        assertEquals(BlockEntitySettledGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Positions sampled:   8"), r.out());
        assertTrue(r.out().contains("Diverged positions:  0"), r.out());
    }

    @Test
    void failOnAnyDivergenceWithDefaultThreshold() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 8, 1)));
        Run r = invoke(log.toString());
        assertEquals(BlockEntitySettledGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Diverged positions:  1"), r.out());
    }

    @Test
    void toleranceThresholdAllowsSmallDivergence() throws IOException {
        // 1 of 20 diverged = 0.05; a 0.1 threshold must PASS.
        Path log = writeLog(List.of(settledLine(100, 20, 1)));
        Run r = invoke(log.toString(), "0.1");
        assertEquals(BlockEntitySettledGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(settledLine(100, 10, 0));
        lines.add(settledLine(200, 10, 1));   // 1 diverged out of 20 total
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0.0");
        assertEquals(BlockEntitySettledGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Settled snapshots:   2"), r.out());
        assertTrue(r.out().contains("Positions sampled:   20"), r.out());
    }

    @Test
    void inconclusiveWhenNoSettledLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(BlockEntitySettledGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresBeCasDiagLines() throws IOException {
        // A BE-CAS-DIAG line (the per-tick delta stream) must NOT be picked up here.
        String cascade = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-CAS-DIAG: HOPPER "
            + "WorldPos[dimensionId=0, x=0, y=64, z=0] cooldown=7 self-slots 48→48 "
            + "above(cross-region/absent) slot0 0→0 output(cross-region/absent) slot0 64→64 [no-change]";
        Path log = writeLog(List.of(cascade));
        Run r = invoke(log.toString());
        assertEquals(BlockEntitySettledGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(BlockEntitySettledGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(BlockEntitySettledGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonNumericThreshold() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 1, 0)));
        Run r = invoke(log.toString(), "notadouble");
        assertEquals(BlockEntitySettledGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("maxDivergenceRate must be a double"), r.err());
    }

    @Test
    void usageErrorOnOutOfRangeThreshold() throws IOException {
        Path log = writeLog(List.of(settledLine(100, 1, 0)));
        Run r = invoke(log.toString(), "1.5");
        assertEquals(BlockEntitySettledGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("maxDivergenceRate"), r.err());
    }
}
