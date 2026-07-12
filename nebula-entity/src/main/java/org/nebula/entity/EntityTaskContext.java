package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.random.FidelityTier;
import org.nebula.core.state.EntityField;

/**
 * Execution context passed to entity physics actions. Provides typed access to
 * {@link EntityPhysicsState} through a per-task {@link EntityStateSnapshot},
 * ensuring all reads are versioned and all writes are buffered until commit.
 *
 * <p>Field access is by entity ID + field path, matching the coordinates that
 * {@link EntityTaskFactory}'s RW-set templates declare (e.g. "position",
 * "velocity", "health").
 *
 * <p>RNG-consuming actions (AI goal selection, damage rolls) draw from a
 * per-task {@link DeterministicRandom} seeded by the layered random source for
 * this task's coordinate — so the stream is identical across runs regardless of
 * execution order. The context is non-RNG by default; {@link #random()} throws
 * if no source was provided, catching actions that consume RNG without
 * declaring {@code RandomUsage}.
 *
 * <p>Under {@link FidelityTier#T2} relaxed determinism, AI-perception reads
 * ({@link #readScalarStale(long, String)} / {@link #readVecStale(long, String)})
 * consult the previous-tick snapshot captured by {@link EntityTaskRunner} — a
 * one-tick freshness lag accepted for throughput on high-player-count servers.
 * Live reads ({@link #readScalar(long, String)} / {@link #readVec(long, String)})
 * are unchanged.
 */
public final class EntityTaskContext {

    private final EntityTaskRunner runner;
    private final EntityPhysicsState state;
    private final EntityStateSnapshot snapshot;
    private final DeterministicRandom random;
    private final TerrainView terrain;
    private final EntityAccessTracer tracer;

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot) {
        this(null, state, snapshot, null, TerrainView.EMPTY, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot, DeterministicRandom random) {
        this(null, state, snapshot, random, TerrainView.EMPTY, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain) {
        this(null, state, snapshot, random, terrain, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain, EntityAccessTracer tracer) {
        this(null, state, snapshot, random, terrain, tracer);
    }

    /**
     * Full constructor used by {@link EntityTaskRunner} when it can pass itself
     * in for stale-AI-snapshot lookups (see {@link #readScalarStale}).
     * {@code runner} may be {@code null} for callers that don't need that path.
     */
    EntityTaskContext(EntityTaskRunner runner, EntityPhysicsState state, EntityStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain, EntityAccessTracer tracer) {
        this.runner = runner;
        this.state = state;
        this.snapshot = snapshot;
        this.random = random;
        TerrainView delegate = terrain == null ? TerrainView.EMPTY : terrain;
        this.terrain = tracer == null ? delegate : pos -> {
            tracer.onBlockRead(pos);
            return delegate.isSolid(pos);
        };
        this.tracer = tracer;
    }

    public Vec3 readVec(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readVec(state, target);
    }

    public double readScalar(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readScalar(state, target);
    }

    public void writeVec(long entityId, String field, Vec3 value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    public void writeScalar(long entityId, String field, double value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    /**
     * Read-only scalar intended for AI perception. Under
     * {@link FidelityTier#useStaleAiSnapshot()} returns the previous-tick
     * value if the runner captured it (T2+); otherwise falls back to the live
     * read. Only fields the runner's per-tick capture keeps are eligible —
     * currently {@code ai_state.*} and {@code position_snapshot}.
     */
    public double readScalarStale(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (runner != null && runner.useStaleAiSnapshot()) {
            Object prev = runner.readPreviousTickSnapshot(target);
            if (prev instanceof Double d) {
                if (tracer != null) tracer.onFieldRead(target);
                return d;
            }
        }
        return readScalar(entityId, field);
    }

    /**
     * Vector twin of {@link #readScalarStale(long, String)}.
     */
    public Vec3 readVecStale(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (runner != null && runner.useStaleAiSnapshot()) {
            Object prev = runner.readPreviousTickSnapshot(target);
            if (prev instanceof Vec3 v) {
                if (tracer != null) tracer.onFieldRead(target);
                return v;
            }
        }
        return readVec(entityId, field);
    }

    /** Read-only terrain oracle for collision checks (defaults to open void). */
    public TerrainView terrain() {
        return terrain;
    }

    /**
     * The per-task deterministic RNG stream for this task's coordinate.
     *
     * @throws IllegalStateException if the action consumes RNG but the task was
     *         not given a random source (i.e. its RW-set declared no
     *         {@code RandomUsage}) — surfacing an undeclared-RNG bug rather than
     *         silently diverging.
     */
    public DeterministicRandom random() {
        if (random == null) {
            throw new IllegalStateException(
                "Task consumed RNG but declared no RandomUsage in its RW-set");
        }
        return random;
    }

    EntityStateSnapshot snapshot() {
        return snapshot;
    }
}