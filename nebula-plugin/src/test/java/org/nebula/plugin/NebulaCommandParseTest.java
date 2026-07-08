package org.nebula.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the pure {@code /nebula capture start} argument parser
 * ({@link NebulaCommand#parseCaptureStart}), which gates the new live-load
 * {@code --drive <seed>} mode. Kept off any live plugin so the flag grammar is
 * verifiable without a running Folia server.
 */
class NebulaCommandParseTest {

    private static String[] args(String... rest) {
        String[] full = new String[rest.length + 2];
        full[0] = "capture";
        full[1] = "start";
        System.arraycopy(rest, 0, full, 2, rest.length);
        return full;
    }

    @Test
    void defaultsToStaticThousandTickCapture() {
        var r = NebulaCommand.parseCaptureStart(args());
        assertNull(r.error());
        assertEquals(1000L, r.ticks());
        assertNull(r.driveSeed(), "no --drive ⇒ static capture (null seed)");
        assertEquals(NebulaPlugin.DEFAULT_DRIVE_PERIOD, r.period());
    }

    @Test
    void parsesExplicitTickCount() {
        var r = NebulaCommand.parseCaptureStart(args("5000"));
        assertNull(r.error());
        assertEquals(5000L, r.ticks());
        assertNull(r.driveSeed());
    }

    @Test
    void rejectsNonNumericTickCount() {
        var r = NebulaCommand.parseCaptureStart(args("abc"));
        assertNotNull(r.error());
    }

    @Test
    void parsesDriveSeed() {
        var r = NebulaCommand.parseCaptureStart(args("10000", "--drive", "42"));
        assertNull(r.error());
        assertEquals(10000L, r.ticks());
        assertEquals(42L, r.driveSeed());
        assertEquals(NebulaPlugin.DEFAULT_DRIVE_PERIOD, r.period());
    }

    @Test
    void parsesDriveSeedAndPeriodInEitherOrder() {
        var a = NebulaCommand.parseCaptureStart(args("100", "--drive", "7", "--period", "16"));
        assertNull(a.error());
        assertEquals(7L, a.driveSeed());
        assertEquals(16, a.period());

        var b = NebulaCommand.parseCaptureStart(args("100", "--period", "16", "--drive", "7"));
        assertNull(b.error());
        assertEquals(7L, b.driveSeed());
        assertEquals(16, b.period());
    }

    @Test
    void acceptsNegativeSeed() {
        var r = NebulaCommand.parseCaptureStart(args("100", "--drive", "-9"));
        assertNull(r.error());
        assertEquals(-9L, r.driveSeed());
    }

    @Test
    void driveWithoutSeedIsAnError() {
        assertNotNull(NebulaCommand.parseCaptureStart(args("100", "--drive")).error());
    }

    @Test
    void nonNumericSeedIsAnError() {
        assertNotNull(NebulaCommand.parseCaptureStart(args("100", "--drive", "xyz")).error());
    }

    @Test
    void periodWithoutValueIsAnError() {
        assertNotNull(NebulaCommand.parseCaptureStart(args("100", "--drive", "1", "--period")).error());
    }

    @Test
    void periodBelowOneIsAnError() {
        assertNotNull(NebulaCommand.parseCaptureStart(args("100", "--drive", "1", "--period", "0")).error());
    }

    @Test
    void unknownOptionIsAnError() {
        assertNotNull(NebulaCommand.parseCaptureStart(args("100", "--bogus")).error());
    }
}
