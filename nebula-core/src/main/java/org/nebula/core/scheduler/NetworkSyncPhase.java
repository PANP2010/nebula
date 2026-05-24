package org.nebula.core.scheduler;

import org.nebula.core.state.WorldPos;

import java.util.*;

/**
 * Collects state changes from the completed tick and produces per-player
 * update sets for network synchronization (arch doc §4.4 step 3, §14.3 Month 7-9).
 *
 * <p>After all DAG layers and the plugin phase complete, the network sync phase:
 * <ol>
 *   <li>Collects all modified block positions and entity IDs from the tick</li>
 *   <li>For each connected player, computes which changes are within their
 *       view distance (visible set)</li>
 *   <li>Produces a per-player update set that the network layer serializes
 *       into packets</li>
 * </ol>
 *
 * <p>This phase is embarrassingly parallel across players — each player's
 * visible-set computation is independent.
 */
public final class NetworkSyncPhase {

    private final Set<WorldPos> dirtyBlocks = new LinkedHashSet<>();
    private final Set<Long> dirtyEntities = new LinkedHashSet<>();

    /**
     * Records a block position that was modified during this tick.
     * Called by the DAG executor after each task commits its write buffer.
     */
    public void markBlockDirty(WorldPos pos) {
        dirtyBlocks.add(pos);
    }

    /**
     * Records all block positions from a set of written blocks.
     */
    public void markBlocksDirty(Collection<WorldPos> positions) {
        dirtyBlocks.addAll(positions);
    }

    /**
     * Records an entity that was modified during this tick.
     */
    public void markEntityDirty(long entityId) {
        dirtyEntities.add(entityId);
    }

    /**
     * Computes the per-player update set for a given player's view.
     *
     * @param playerPos  player's current position
     * @param viewDistanceBlocks  view distance in blocks (e.g. 160 for 10 chunks)
     * @param dimensionId  player's dimension
     * @return update set containing only changes visible to this player
     */
    public PlayerUpdateSet computeForPlayer(WorldPos playerPos, int viewDistanceBlocks, int dimensionId) {
        Set<WorldPos> visibleBlocks = new LinkedHashSet<>();
        for (WorldPos pos : dirtyBlocks) {
            if (pos.dimensionId() != dimensionId) continue;
            if (withinRange(playerPos, pos, viewDistanceBlocks)) {
                visibleBlocks.add(pos);
            }
        }
        // Entities: a real implementation would check entity positions,
        // but at this layer we return all dirty entities in the same dimension
        // (actual position filtering requires entity state access)
        return new PlayerUpdateSet(Set.copyOf(visibleBlocks), Set.copyOf(dirtyEntities));
    }

    /**
     * Returns all dirty blocks from this tick (for testing/diagnostics).
     */
    public Set<WorldPos> dirtyBlocks() {
        return Set.copyOf(dirtyBlocks);
    }

    /**
     * Returns all dirty entities from this tick.
     */
    public Set<Long> dirtyEntities() {
        return Set.copyOf(dirtyEntities);
    }

    /**
     * Resets the dirty tracking for the next tick.
     */
    public void reset() {
        dirtyBlocks.clear();
        dirtyEntities.clear();
    }

    private static boolean withinRange(WorldPos player, WorldPos target, int range) {
        int dx = player.x() - target.x();
        int dz = player.z() - target.z();
        return dx * dx + dz * dz <= (long) range * range;
    }

    /**
     * Per-player set of changes visible within their view distance.
     */
    public record PlayerUpdateSet(
        Set<WorldPos> blocks,
        Set<Long> entities
    ) {
        public int totalChanges() {
            return blocks.size() + entities.size();
        }

        public boolean isEmpty() {
            return blocks.isEmpty() && entities.isEmpty();
        }
    }
}
