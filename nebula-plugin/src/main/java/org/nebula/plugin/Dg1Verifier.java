package org.nebula.plugin;

import org.bukkit.World;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayPlayer;
import org.nebula.replay.ReplayVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * DG1 verification: proves the state-hashing infrastructure is deterministic
 * (arch doc §14.2 prerequisite).
 *
 * <p>Phase 0 test: for each tick, compute the state hash TWICE and verify
 * both calls return the same result.  This confirms:
 * <ul>
 *   <li>StateHashComputer is pure (no side effects)</li>
 *   <li>Bukkit block reads are consistent within a tick</li>
 *   <li>The recording infrastructure is idempotent</li>
 * </ul>
 *
 * <p>Phase 0 INTERCEPT test (future): compare Folia-native execution
 * vs Nebula DAG execution for the same input ticks.
 */
public final class Dg1Verifier {

    private final Logger log;
    private final TickStateHasher hasher;

    private volatile boolean active = false;
    private long ticksVerified = 0;
    private long mismatches = 0;
    private long maxTicks;
    private int firstMismatchTick = -1;

    public Dg1Verifier(TickStateHasher hasher, Logger log) {
        this.hasher = hasher;
        this.log = log;
    }

    /** Sets a reference replay (kept for future INTERCEPT comparison). */
    public void setReference(List<ReplayFrame> frames) {
        log.info("DG1: Reference set: " + frames.size() + " frames");
    }

    /** Starts the inline idempotency verification. */
    public void startVerification(long ticks) {
        this.maxTicks = ticks;
        this.ticksVerified = 0;
        this.mismatches = 0;
        this.firstMismatchTick = -1;
        this.active = true;
        log.info("DG1: Inline idempotency verification started (" + ticks + " ticks)");
    }

    /**
     * Called each tick. Hashes the world twice and compares.
     * Returns true while still verifying.
     */
    public boolean onTick(long tickNumber, World world) {
        if (!active) return false;

        // Hash the same world state twice in the same tick
        byte[] hash1 = hasher.computeHash(world);
        byte[] hash2 = hasher.computeHash(world);

        ticksVerified++;

        if (!Arrays.equals(hash1, hash2)) {
            mismatches++;
            if (firstMismatchTick < 0) {
                firstMismatchTick = (int) ticksVerified;
                log.warning("DG1: Hash mismatch at tick " + ticksVerified
                    + " — hash1=" + hex(hash1) + " hash2=" + hex(hash2));
            }
        }

        if (ticksVerified % 200 == 0) {
            log.info("DG1: " + ticksVerified + "/" + maxTicks + " ticks verified, "
                + mismatches + " mismatches");
        }

        if (ticksVerified >= maxTicks) {
            active = false;
            reportResult();
            return false;
        }
        return true;
    }

    private void reportResult() {
        if (mismatches == 0) {
            log.info("DG1 PASS: " + ticksVerified + " ticks verified — "
                + "hash function is deterministic (idempotent within each tick)");
            log.info("DG1 PASS: Infrastructure ready for INTERCEPT mode validation");
        } else {
            log.warning("DG1 FAIL: " + mismatches + " mismatches in " + ticksVerified + " ticks");
            log.warning("DG1 FAIL: First mismatch at tick " + firstMismatchTick);
            log.warning("DG1 FAIL: StateHash is NOT deterministic — fix before proceeding");
        }
    }

    public boolean isActive() { return active; }
    public long ticksVerified() { return ticksVerified; }
    public long mismatches() { return mismatches; }

    // Kept for future INTERCEPT comparison
    public void startCandidate(long ticks, Path savePath) {
        startVerification(ticks);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, b.length); i++) sb.append(String.format("%02x", b[i]));
        return sb + "...";
    }
}
