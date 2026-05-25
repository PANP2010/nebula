package org.nebula.redstone;

import org.nebula.core.state.WorldPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-task write buffer for redstone state, backed by versioned reads from
 * {@link RedstoneWorldState}.
 *
 * <p>Workflow:
 * <ol>
 *   <li>At task start, the snapshot reads positions from the global state,
 *       capturing version stamps.</li>
 *   <li>During execution, writes accumulate locally without touching global state.</li>
 *   <li>At commit time, each write is CAS'd against the expected version.
 *       If all positions commit successfully the snapshot is done; if any
 *       position's version advanced (stale read), commit fails and the caller
 *       can retry or escalate.</li>
 * </ol>
 *
 * <p>This design follows arch doc §4.4: "all writes go into a per-task buffer;
 * after the layer finishes, buffers are committed via fine-grained CAS."
 */
public final class RedstoneStateSnapshot {

    private final Map<WorldPos, Long> readVersions = new LinkedHashMap<>();
    private final Map<WorldPos, Integer> pendingPowerLevels = new LinkedHashMap<>();
    private final Map<WorldPos, Map<String, Object>> pendingInternalState = new LinkedHashMap<>();
    private boolean committed = false;

    /**
     * Reads the power level at {@code pos} from the global state, caching the
     * version for later CAS commit.
     *
     * @return the current power level, or -1 if the position has no entry
     */
    public int readPowerLevel(RedstoneWorldState world, WorldPos pos) {
        checkNotCommitted();
        RedstoneWorldState.ReadStamp stamp = world.readPowerLevel(pos);
        readVersions.putIfAbsent(pos, stamp.version());
        return world.getPowerLevel(pos);
    }

    /**
     * Reads an internal state value at {@code pos}, capturing the version.
     */
    public Object readInternalState(RedstoneWorldState world, WorldPos pos, String key) {
        checkNotCommitted();
        RedstoneWorldState.ReadStamp stamp = world.readPowerLevel(pos);
        readVersions.putIfAbsent(pos, stamp.version());
        return world.getInternalState(pos, key);
    }

    /**
     * Buffers a power level write. Not applied until {@link #commit}.
     */
    public void setPowerLevel(WorldPos pos, int level) {
        checkNotCommitted();
        pendingPowerLevels.put(pos, level);
    }

    /**
     * Buffers an internal state write. Not applied until {@link #commit}.
     */
    public void setInternalState(WorldPos pos, String key, Object value) {
        checkNotCommitted();
        pendingInternalState.computeIfAbsent(pos, k -> new HashMap<>()).put(key, value);
    }

    /**
     * Attempts to commit all pending writes to {@code world} via CAS.
     *
     * @return a {@link CommitResult} indicating success or listing the
     *         positions that failed due to version mismatch
     */
    public CommitResult commit(RedstoneWorldState world) {
        checkNotCommitted();
        committed = true;

        Map<WorldPos, Long> failedPositions = new LinkedHashMap<>();

        Set<WorldPos> allWritePositions = new java.util.LinkedHashSet<>();
        allWritePositions.addAll(pendingPowerLevels.keySet());
        allWritePositions.addAll(pendingInternalState.keySet());

        for (WorldPos pos : allWritePositions) {
            // If we never read this position, capture version now ("late read").
            // Safe because the DAG guarantees no concurrent writer in same layer.
            long expectedVersion = readVersions.containsKey(pos)
                ? readVersions.get(pos)
                : world.getVersion(pos);
            int powerLevel = pendingPowerLevels.getOrDefault(pos, world.getPowerLevel(pos));
            Map<String, Object> internalState = pendingInternalState.getOrDefault(pos, Map.of());

            boolean success = world.casCommit(pos, expectedVersion, powerLevel, internalState);
            if (!success) {
                failedPositions.put(pos, expectedVersion);
            }
        }

        return failedPositions.isEmpty()
            ? CommitResult.SUCCESS
            : new CommitResult(false, failedPositions);
    }

    /**
     * Discards all pending writes without committing.
     */
    public void discard() {
        checkNotCommitted();
        committed = true;
        pendingPowerLevels.clear();
        pendingInternalState.clear();
        readVersions.clear();
    }

    /**
     * Merges another snapshot's pending writes into this one.
     * Used when combining task results within the same DAG layer
     * (disjoint positions guaranteed by dependency analysis).
     */
    public void mergeFrom(RedstoneStateSnapshot other) {
        checkNotCommitted();
        pendingPowerLevels.putAll(other.pendingPowerLevels);
        other.pendingInternalState.forEach((pos, states) ->
            pendingInternalState.computeIfAbsent(pos, k -> new HashMap<>()).putAll(states));
        other.readVersions.forEach(readVersions::putIfAbsent);
    }

    public Set<WorldPos> changedPositions() {
        var positions = new java.util.LinkedHashSet<WorldPos>();
        positions.addAll(pendingPowerLevels.keySet());
        positions.addAll(pendingInternalState.keySet());
        return Set.copyOf(positions);
    }

    public int pendingChangeCount() {
        return changedPositions().size();
    }

    public boolean isEmpty() {
        return pendingPowerLevels.isEmpty() && pendingInternalState.isEmpty();
    }

    public boolean isCommitted() {
        return committed;
    }

    public int getPendingPowerLevel(WorldPos pos) {
        return pendingPowerLevels.getOrDefault(pos, -1);
    }

    public void clear() {
        pendingPowerLevels.clear();
        pendingInternalState.clear();
        readVersions.clear();
        committed = false;
    }

    private void checkNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Snapshot already committed or discarded");
        }
    }

    public record CommitResult(boolean success, Map<WorldPos, Long> failedPositions) {
        public static final CommitResult SUCCESS = new CommitResult(true, Map.of());

        public CommitResult {
            failedPositions = failedPositions == null ? Map.of() : Map.copyOf(failedPositions);
        }
    }
}
