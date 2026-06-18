package org.nebula.folia;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RedstoneWire;
import org.nebula.core.state.WorldPos;
import org.nebula.folia.bridge.FoliaCaptureHarness;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Computes a deterministic SHA-256 hash of redstone world state for zero-diff verification.
 *
 * <p>Hash inputs (in order):
 * <ol>
 *   <li>Tick number (8 bytes, little-endian)</li>
 *   <li>World name UTF-8 bytes</li>
 *   <li>For each redstone component position (sorted by x→y→z):
 *     <ul>
 *       <li>Position: x(4) + y(4) + z(4) little-endian</li>
 *       <li>Power level (1 byte)</li>
 *       <li>For RedstoneWire: connection states per face (6 bytes)</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Designed to be region-thread-safe: each call reads the current state independently.
 * The hash is deterministic if the world state is identical at the same tick.
 */
public final class WorldStateHasher implements FoliaCaptureHarness.StateHasher {

    private static final Logger LOG = Logger.getLogger(WorldStateHasher.class.getName());

    /** Decodes a WorldPos from position bytes. */
    private final Function<byte[], WorldPos> posDecoder;

    /** Positions to include in hash (loaded lazily from world scan or injected). */
    private final Set<WorldPos> trackedPositions = ConcurrentHashMap.newKeySet();

    public WorldStateHasher(Function<byte[], WorldPos> posDecoder) {
        this.posDecoder = posDecoder;
    }

    /**
     * Adds a position to the tracked set. Called by chunk scanners or manually.
     */
    public void trackPosition(WorldPos pos) {
        trackedPositions.add(pos);
    }

    /**
     * Removes a position from the tracked set.
     */
    public void untrackPosition(WorldPos pos) {
        trackedPositions.remove(pos);
    }

    /**
     * Returns the current tracked positions.
     */
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

        // Tick number (8 bytes, little-endian)
        digest.update(longToBytesLE(tickNumber));

        // World name
        digest.update(world.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Redstone component states
        for (WorldPos pos : sortedPositions()) {
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            BlockData data = block.getBlockData();

            if (data instanceof AnaloguePowerable ap) {
                // Position (12 bytes)
                digest.update(intToBytesLE(pos.x()));
                digest.update(intToBytesLE(pos.y()));
                digest.update(intToBytesLE(pos.z()));

                // Power level (1 byte)
                digest.update((byte) ap.getPower());

                // RedstoneWire connections (6 bytes: N,S,E,W,U,D)
                if (data instanceof RedstoneWire wire) {
                    for (org.bukkit.block.BlockFace face : orderedFaces()) {
                        RedstoneWire.Connection conn = wire.getFace(face);
                        digest.update((byte) conn.ordinal());
                    }
                }
            }
        }

        return digest.digest();
    }

    private Iterable<WorldPos> sortedPositions() {
        // Sort by x → y → z for deterministic ordering
        return trackedPositions.stream()
            .sorted((a, b) -> {
                int cmp = Integer.compare(a.x(), b.x());
                if (cmp != 0) return cmp;
                cmp = Integer.compare(a.y(), b.y());
                if (cmp != 0) return cmp;
                return Integer.compare(a.z(), b.z());
            })
            .toList();
    }

    private static org.bukkit.block.BlockFace[] orderedFaces() {
        return new org.bukkit.block.BlockFace[]{
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.WEST,
            org.bukkit.block.BlockFace.UP,
            org.bukkit.block.BlockFace.DOWN
        };
    }

    private static byte[] longToBytesLE(long v) {
        byte[] b = new byte[8];
        b[0] = (byte) v;
        b[1] = (byte) (v >> 8);
        b[2] = (byte) (v >> 16);
        b[3] = (byte) (v >> 24);
        b[4] = (byte) (v >> 32);
        b[5] = (byte) (v >> 40);
        b[6] = (byte) (v >> 48);
        b[7] = (byte) (v >> 56);
        return b;
    }

    private static byte[] intToBytesLE(int v) {
        byte[] b = new byte[4];
        b[0] = (byte) v;
        b[1] = (byte) (v >> 8);
        b[2] = (byte) (v >> 16);
        b[3] = (byte) (v >> 24);
        return b;
    }
}