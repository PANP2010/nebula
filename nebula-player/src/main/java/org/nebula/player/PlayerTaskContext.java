package org.nebula.player;

import org.nebula.core.math.Vec3;
import org.nebula.core.player.PlayerField;
import org.nebula.core.player.PlayerPhysicsState;
import org.nebula.core.player.PlayerSnapshot;
import org.nebula.core.player.PlayerStateSnapshot;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.TerrainView;

import java.util.UUID;

/**
 * Execution context passed to player actions. Mirrors {@link org.nebula.entity.EntityTaskContext}:
 * all reads are versioned via {@link PlayerStateSnapshot}, all writes buffered until layer commit.
 *
 * <p>Field access is by player {@link UUID} + field path. Terrain reads use the
 * Bukkit-backed {@link TerrainView} so player movement resolves against real block data.
 *
 * <p>The context optionally provides a per-task {@link DeterministicRandom} seeded from
 * the layered random source — survival RNG (block break time, crop growth, etc.) must
 * be deterministic to preserve the DAG's zero-diff claim.
 */
public final class PlayerTaskContext {

    private final PlayerPhysicsState state;
    private final PlayerStateSnapshot snapshot;
    private final DeterministicRandom random;
    private final TerrainView terrain;
    private final PlayerAccessTracer tracer;

    PlayerTaskContext(PlayerPhysicsState state, PlayerStateSnapshot snapshot) {
        this(state, snapshot, null, TerrainView.EMPTY, null);
    }

    PlayerTaskContext(PlayerPhysicsState state, PlayerStateSnapshot snapshot,
                     DeterministicRandom random) {
        this(state, snapshot, random, TerrainView.EMPTY, null);
    }

    PlayerTaskContext(PlayerPhysicsState state, PlayerStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain) {
        this(state, snapshot, random, terrain, null);
    }

    PlayerTaskContext(PlayerPhysicsState state, PlayerStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain, PlayerAccessTracer tracer) {
        this.state = state;
        this.snapshot = snapshot;
        this.random = random;
        this.terrain = tracer == null ? terrain : pos -> {
            if (tracer != null) tracer.onBlockRead(pos);
            return terrain.isSolid(pos);
        };
        this.tracer = tracer;
    }

    // ── Vector reads/writes ────────────────────────────────────────────────────

    public Vec3 readVec(UUID playerId, String field) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readVec(state, target);
    }

    public void writeVec(UUID playerId, String field, Vec3 value) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    // ── Scalar reads/writes ─────────────────────────────────────────────────────

    public double readScalar(UUID playerId, String field) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readScalar(state, target);
    }

    public void writeScalar(UUID playerId, String field, double value) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    // ── Boolean reads/writes ───────────────────────────────────────────────────

    public boolean readBool(UUID playerId, String field) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readBool(state, target);
    }

    public void writeBool(UUID playerId, String field, boolean value) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    // ── String reads/writes ─────────────────────────────────────────────────────

    public String readString(UUID playerId, String field) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readString(state, target);
    }

    public void writeString(UUID playerId, String field, String value) {
        PlayerField target = new PlayerField(playerId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    // ── Long-entity reads/writes (for targeting other entities in combat/interact) ──

    public Vec3 readVec(long entityId, String field) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readVec(state, target);
    }

    public void writeVec(long entityId, String field, Vec3 value) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    public double readScalar(long entityId, String field) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readScalar(state, target);
    }

    public void writeScalar(long entityId, String field, double value) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    public boolean readBool(long entityId, String field) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readBool(state, target);
    }

    public void writeBool(long entityId, String field, boolean value) {
        PlayerField target = PlayerField.ofEntity(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    // ── Terrain ────────────────────────────────────────────────────────────────

    /** Read-only terrain oracle for collision checks. */
    public TerrainView terrain() {
        return terrain;
    }

    // ── RNG ────────────────────────────────────────────────────────────────────

    /**
     * Per-task deterministic RNG. Survival mechanics that need random (block break time,
     * crop growth, etc.) call this. Throws if no random source was provided.
     */
    public DeterministicRandom random() {
        if (random == null) {
            throw new IllegalStateException(
                "Task consumed RNG but declared no RandomUsage in its RW-set");
        }
        return random;
    }

    PlayerStateSnapshot snapshot() {
        return snapshot;
    }
}
