package org.nebula.replay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Computes a deterministic SHA-256 hash of the game state at the end of each tick.
 *
 * <p>The hash covers all state that participates in the tick function:
 * <ul>
 *   <li>Block states (position → block-state-ID)</li>
 *   <li>Block entity NBT (position → serialised bytes)</li>
 *   <li>Entity data (ID → serialised bytes)</li>
 *   <li>Global state (game time, weather, difficulty, game rules)</li>
 * </ul>
 *
 * <p>Iteration order is deterministic: blocks by (dim, x, y, z), entities by
 * ID, globals by key.  This guarantees the same logical state always produces
 * the same hash, regardless of internal data structure ordering.
 */
public final class StateHashComputer {

    private static final Logger LOG = Logger.getLogger(StateHashComputer.class.getName());

    /**
     * Computes the state hash from a serialised state snapshot.
     *
     * @param stateChunks map of (dimension → serialised chunk data) — block states
     * @param blockEntities map of (position key → serialised NBT)
     * @param entities map of (entity ID → serialised data)
     * @param globalState ordered map of global keys to serialised values
     * @return 32-byte SHA-256 hash
     */
    public static byte[] compute(
        Map<String, byte[]> stateChunks,
        Map<String, byte[]> blockEntities,
        Map<String, byte[]> entities,
        Map<String, byte[]> globalState
    ) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Header — version the serialisation format so old replays break cleanly
            md.update("NEBULA-REPLAY-v2\n".getBytes(StandardCharsets.UTF_8));

            absorbCategory(md, "BLK", stateChunks);
            absorbCategory(md, "BE", blockEntities);
            absorbCategory(md, "ENT", entities);
            absorbCategory(md, "GLB", globalState);

            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    /** Convenience: compute hash from an empty world (seed only). */
    public static byte[] computeSeedHash(long worldSeed) {
        return compute(Map.of("seed", longToBytes(worldSeed)), Map.of(), Map.of(), Map.of());
    }

    private static void absorbCategory(MessageDigest md, String tag, Map<String, byte[]> entries) {
        md.update(tag.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
        TreeMap<String, byte[]> sorted = new TreeMap<>(entries);
        for (var entry : sorted.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            md.update(ByteBuffer.allocate(4).putInt(keyBytes.length).array());
            md.update(keyBytes);
            md.update(ByteBuffer.allocate(4).putInt(entry.getValue().length).array());
            md.update(entry.getValue());
        }
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }
}
