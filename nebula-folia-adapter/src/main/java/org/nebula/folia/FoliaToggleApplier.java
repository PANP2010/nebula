package org.nebula.folia;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.nebula.core.state.WorldPos;
import org.nebula.replay.ResolvedToggle;
import org.nebula.replay.ToggleApplier;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * The live-Folia implementation of the {@link ToggleApplier} seam that
 * {@link org.nebula.replay.LiveLoadToggleDriver} delegates to. It takes a
 * resolved (position, powered) toggle and flips the real lever/switch at that
 * position on the region thread Folia says owns it, firing the same
 * neighbour-update path a player pulling the lever would — so the observe-only
 * DAG shadow sees the change exactly as it sees an organic redstone edit.
 *
 * <p>This is the concrete realization of the driver's one Folia/NMS step against
 * the real Folia 26.1.2 API. Like {@link FoliaRegionBridge} and
 * {@link NmsBlockStateBridge}, it is constructed with injected collaborators (a
 * {@link FoliaRegionBridge} for region dispatch and a {@link World}) so it can be
 * unit-tested with Proxy-stubbed Folia interfaces — no running Minecraft.
 *
 * <h3>Why this fires updates instead of a bare block write</h3>
 * The contract on {@link ToggleApplier} requires that applying the toggle fire
 * the neighbour-update path, not suppress it: the whole point of the live-load
 * driver is to exercise Nebula's pipeline under real redstone activity, and the
 * observe-only shadow only wakes on {@code BLOCK_UPDATE}s. We therefore write via
 * {@link Block#setBlockData(BlockData, boolean)} with {@code applyPhysics=true}
 * (the default overload does the same), which triggers Folia's block-update
 * machinery. A suppress-updates write would silently defeat the harness — the
 * same class of invisible gap as the B3 key-mismatch wound this driver is built
 * to avoid.
 *
 * <h3>Region-thread safety</h3>
 * Every apply is dispatched through {@link FoliaRegionBridge#runOnRegion}, so the
 * {@code setBlockData} call always runs on the thread that owns the target block.
 * {@link #apply} itself may be called from the global tick thread (where the
 * capture loop lives); it returns immediately after scheduling.
 */
public final class FoliaToggleApplier implements ToggleApplier {

    private static final Logger LOG = Logger.getLogger(FoliaToggleApplier.class.getName());

    private final FoliaRegionBridge regionBridge;
    private final World world;

    /**
     * @param regionBridge dispatches the apply to the owning region thread
     * @param world        the world the toggle positions live in
     */
    public FoliaToggleApplier(FoliaRegionBridge regionBridge, World world) {
        this.regionBridge = Objects.requireNonNull(regionBridge, "regionBridge");
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Schedules the lever/switch at {@code toggle.position()} to be set to
     * {@code toggle.powered()} on its owning region thread. Returns immediately;
     * the actual world write happens when the region scheduler runs the task.
     */
    @Override
    public void apply(long tick, ResolvedToggle toggle) {
        Objects.requireNonNull(toggle, "toggle");
        WorldPos pos = toggle.position();
        boolean powered = toggle.powered();
        regionBridge.runOnRegion(world, pos, () -> setPowered(pos, powered, tick));
    }

    /**
     * Region-thread body: read the block, flip its {@link Powerable} state, and
     * write it back with physics so neighbour updates fire. No-op (with a warning)
     * if the block is not powerable — a setup error, since the driver's bindings
     * are supposed to point at levers/switches.
     *
     * <p>Package-private so the region-thread logic can be unit-tested directly
     * without going through the scheduler proxy.
     */
    void setPowered(WorldPos pos, boolean powered, long tick) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockData data = block.getBlockData();
        if (!(data instanceof Powerable powerable)) {
            LOG.warning(() -> "Toggle target at " + pos + " is not Powerable (type="
                + block.getType() + ") — cannot toggle; check the driver's source bindings");
            return;
        }
        if (powerable.isPowered() == powered) {
            // Already in the target state — nothing to change, and re-writing the
            // same state would emit a spurious update. Skip so the toggle stream
            // stays honest about which ticks actually change the world.
            LOG.fine(() -> "Toggle at " + pos + " already powered=" + powered + " (tick " + tick + ") — skipped");
            return;
        }
        powerable.setPowered(powered);
        // applyPhysics=true fires the neighbour-update path the DAG shadow observes.
        block.setBlockData(data, true);
        LOG.fine(() -> "Toggled " + pos + " -> powered=" + powered + " (tick " + tick + ")");
    }
}
