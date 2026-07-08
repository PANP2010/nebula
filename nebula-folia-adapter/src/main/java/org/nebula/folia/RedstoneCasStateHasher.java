package org.nebula.folia;

import org.bukkit.World;
import org.nebula.core.state.WorldPos;
import org.nebula.folia.bridge.FoliaCaptureHarness;
import org.nebula.redstone.RedstoneWorldState;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Computes a deterministic SHA-256 hash of redstone state by reading from the
 * thread-safe {@link RedstoneWorldState} CAS store rather than from live NMS
 * blocks.
 *
 * <p>This is the hasher used for live zero-diff capture on a running Folia
 * server.  The capture harness drives {@link #hashState} from the
 * <em>global tick thread</em>, but Folia only permits block reads on the region
 * thread that owns a chunk — so the NMS-reading {@link WorldStateHasher} throws
 * {@code NullPointerException} ("getCurrentWorldData() is null") when invoked off
 * the region thread.  Reading from the CAS store (a {@link ConcurrentHashMap})
 * avoids that entirely and, more importantly, hashes the state that Nebula's DAG
 * actually computed — which is exactly what zero-diff verification must compare
 * across runs.
 *
 * <p>Hash inputs (in order):
 * <ol>
 *   <li>Tick number (8 bytes, little-endian)</li>
 *   <li>World name UTF-8 bytes</li>
 *   <li>For each tracked position (sorted by dimension→x→y→z):
 *     position (dim,x,y,z as 4×4 bytes LE) + power level (1 byte)</li>
 * </ol>
 *
 * <p>Only positions that have been {@link #trackPosition tracked} are hashed, so
 * the hash covers the redstone components the plugin registered — not unrelated
 * CAS churn.
 */
public final class RedstoneCasStateHasher implements FoliaCaptureHarness.StateHasher {

    private static final Logger LOG = Logger.getLogger(RedstoneCasStateHasher.class.getName());

    private final RedstoneWorldState state;
    private final Set<WorldPos> trackedPositions = ConcurrentHashMap.newKeySet();

    public RedstoneCasStateHasher(RedstoneWorldState state) {
        this.state = state;
    }

    /** Adds a position to the tracked set. */
    public void trackPosition(WorldPos pos) {
        trackedPositions.add(pos);
    }

    /** Removes a position from the tracked set. */
    public void untrackPosition(WorldPos pos) {
        trackedPositions.remove(pos);
    }

    /** Returns an immutable snapshot of the tracked positions. */
    public Set<WorldPos> trackedPositions() {
        return Set.copyOf(trackedPositions);
    }

    @Override
    public byte[] hashState(World world, long tickNumber) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOG.warning("SHA-256 not available: " + e);
            return new byte[0];
        }

        digest.update(longToBytesLE(tickNumber));
        digest.update(world.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (WorldPos pos : sortedPositions()) {
            digest.update(intToBytesLE(pos.dimensionId()));
            digest.update(intToBytesLE(pos.x()));
            digest.update(intToBytesLE(pos.y()));
            digest.update(intToBytesLE(pos.z()));
            // Power level from the CAS store; -1 (unset) is folded to 0 so an
            // unwritten position hashes identically to an explicit power-0 one.
            int power = state.getPowerLevel(pos);
            digest.update((byte) Math.max(0, power));
        }

        return digest.digest();
    }

    private Iterable<WorldPos> sortedPositions() {
        return trackedPositions.stream().sorted().toList();
    }

    private static byte[] longToBytesLE(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    private static byte[] intToBytesLE(int v) {
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }
}
