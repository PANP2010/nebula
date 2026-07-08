package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RedstoneWire;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneWorldState;

import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's CAS state stores and real NMS block state through
 * the Bukkit API. All reads/writes must occur on the region thread that owns
 * the target block — the caller (typically {@link FoliaRegionTickExecutor})
 * is responsible for ensuring this via {@link FoliaRegionBridge#ownsCurrentRegion}.
 *
 * <p>Read path: fetch {@link Block#getBlockData()}, extract power level from
 * {@link AnaloguePowerable#getPower()} if the block is a redstone component,
 * then commit to {@link RedstoneWorldState} via CAS.
 *
 * <p>Write path: construct a new {@link BlockData} with the target power level,
 * call {@link Block#setBlockData(BlockData)}, and update the CAS store.
 *
 * <p>Only redstone wire is bound in this initial implementation. Torches,
 * repeaters, and comparators will be added in subsequent iterations.
 */
public final class NmsBlockStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsBlockStateBridge.class.getName());

    private final RedstoneWorldState casStore;

    public NmsBlockStateBridge(RedstoneWorldState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    /**
     * Reads the current power level from the real block at {@code pos} in
     * {@code world} and commits it to the CAS store. Returns the version stamp
     * for stale-read detection.
     *
     * <p>If the block is not an {@link AnaloguePowerable} (e.g. air, stone),
     * returns 0 and stores -1 in the CAS (no signal).
     *
     * <p>Must be called on the region thread that owns {@code pos}.
     */
    public long syncFromNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockData data = block.getBlockData();

        int power = -1;
        Map<String, Object> internal = new java.util.HashMap<>();

        if (data instanceof AnaloguePowerable ap) {
            power = ap.getPower();
            if (data instanceof RedstoneWire wire) {
                // Capture connection states for internal state
                for (org.bukkit.block.BlockFace face : wire.getAllowedFaces()) {
                    internal.put("conn_" + face.name(), wire.getFace(face).name());
                }
            }
        }

        // Read current version for CAS commit
        long expectedVersion = casStore.getVersion(pos);
        boolean committed = casStore.casCommit(pos, expectedVersion, power, internal);
        if (!committed) {
            LOG.fine(() -> "CAS commit rejected at " + pos + " — version advanced from " + expectedVersion);
        }
        return casStore.getVersion(pos);
    }

    /**
     * Writes {@code power} to the real block at {@code pos} in {@code world}
     * and updates the CAS store. Returns true if the write succeeded.
     *
     * <p>If the block is not an {@link AnaloguePowerable}, does nothing and
     * returns false.
     *
     * <p>Must be called on the region thread that owns {@code pos}.
     */
    public boolean syncToNms(World world, WorldPos pos, int power) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockData data = block.getBlockData();

        if (!(data instanceof AnaloguePowerable ap)) {
            LOG.fine(() -> "Block at " + pos + " is not AnaloguePowerable — cannot set power");
            return false;
        }

        // Clamp to valid range
        int clamped = Math.max(0, Math.min(power, ap.getMaximumPower()));
        ap.setPower(clamped);
        block.setBlockData(data);

        // Update CAS store (unconditional — we just wrote to NMS)
        casStore.putPowerLevel(pos, clamped);

        LOG.fine(() -> "Set power " + clamped + " at " + pos + " (requested " + power + ")");
        return true;
    }

    /**
     * Convenience: syncFromNms for all positions in the CAS store that are
     * within the given world. Used to initialize the CAS store from current
     * world state at startup.
     *
     * <p>Must be called on region threads that own each position. The caller
     * should dispatch positions to their owning regions before calling this.
     */
    public void bulkSyncFromNms(World world, java.util.Set<WorldPos> positions) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(positions, "positions");
        for (WorldPos pos : positions) {
            syncFromNms(world, pos);
        }
    }

    /**
     * Returns the CAS store this bridge is bound to (for direct access).
     */
    public RedstoneWorldState casStore() {
        return casStore;
    }
}