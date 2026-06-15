package org.nebula.folia;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.nebula.core.state.WorldPos;

import java.util.Objects;

/**
 * The live Folia NMS boundary: translates Nebula's coordinate model
 * ({@link WorldPos}) to Folia region-ownership queries and schedules Nebula
 * work onto the region thread that owns a position.
 *
 * <p>This is the concrete realization of the version-free
 * {@code nebula-folia-bridge} abstractions against the real Folia 26.1.2 API.
 * It is constructed with an injected {@link Server} and {@link Plugin} (rather
 * than calling {@code Bukkit} statics directly) so it can be unit-tested with a
 * mock server without a running Minecraft instance.
 *
 * <p>Folia partitions the world into regions, each with its own tick thread; a
 * block may only be touched from the thread that owns its region. Nebula's DAG
 * scheduler produces tasks keyed by {@link WorldPos}; this bridge answers
 * "does the current region own this position?" ({@link #ownsCurrentRegion}) and
 * "run this on the owning region thread" ({@link #runOnRegion}) — the two
 * primitives that let Nebula's per-position work cooperate with Folia's region
 * threading instead of fighting it.
 */
public final class FoliaRegionBridge {

    private final Server server;
    private final Plugin plugin;

    public FoliaRegionBridge(Server server, Plugin plugin) {
        this.server = Objects.requireNonNull(server, "server");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * True if the region that owns {@code pos} is the one ticking on the current
     * thread — i.e. it is safe to touch that block's state right now without
     * cross-region coordination.
     */
    public boolean ownsCurrentRegion(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        // Folia's ownership query takes block X/Z (it resolves the chunk/region).
        return server.isOwnedByCurrentRegion(world, pos.x(), pos.z());
    }

    /**
     * Schedules {@code work} to run on the region thread that owns {@code pos}.
     * If the current thread already owns that region the work could run inline,
     * but we always go through the scheduler so ordering is consistent with
     * Folia's own region task queue.
     */
    public void runOnRegion(World world, WorldPos pos, Runnable work) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(work, "work");
        RegionScheduler scheduler = server.getRegionScheduler();
        // RegionScheduler.execute(plugin, world, chunkX, chunkZ, runnable):
        // chunk coords are block >> 4.
        scheduler.execute(plugin, world, pos.x() >> 4, pos.z() >> 4, work);
    }

    /** Chunk X of a block position (block >> 4) — exposed for region grouping. */
    public static int chunkX(WorldPos pos) {
        return pos.x() >> 4;
    }

    /** Chunk Z of a block position (block >> 4). */
    public static int chunkZ(WorldPos pos) {
        return pos.z() >> 4;
    }
}
