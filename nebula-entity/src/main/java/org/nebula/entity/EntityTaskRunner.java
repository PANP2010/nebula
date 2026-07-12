package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.random.FidelityTier;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.random.RandomBudget;
import org.nebula.core.scheduler.CompoundTask;
import org.nebula.core.scheduler.DeterministicOrdering;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.LayerCommitting;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link TaskRunner} that executes entity physics tasks against a shared
 * {@link EntityPhysicsState}. Mirrors {@code RedstoneTaskRunner}.
 *
 * <p>Each task gets its own {@link EntityStateSnapshot}; after a layer
 * completes the caller invokes {@link #commitLayer()} to CAS-commit all
 * pending writes, retrying stale-read losers.
 *
 * <p>Actions are resolved per task by an {@code actionResolver}: given a
 * task ID it returns the live {@link EntityTaskAction}, or {@code null} for a
 * no-op (pure-read tasks like COLLISION need no action). SCC-contracted
 * compound tasks dispatch each member through the resolver in deterministic
 * order, so live behaviour survives contraction (the same fix applied to the
 * redstone runner).
 *
 * <p>If constructed with a {@link LayeredRandomSource}, each task receives a
 * {@link DeterministicRandom} seeded from {@code (tick, entityId, instance)} —
 * so RNG-consuming actions are reproducible regardless of execution order. The
 * caller sets the current tick via {@link #beginTick(long)}.
 *
 * <p>If additionally given a {@link RandomBudget}, the runner allocates a budget
 * per RNG-declaring task from its declared {@link RandomUsage} estimate and,
 * after execution, evaluates actual {@code callsMade()} against it. This drives
 * the DG2 over-budget metric ("random over-budget re-execution rate &lt;1%",
 * arch doc §11.2), queryable via {@link #currentOverBudgetRate()}.
 */
public final class EntityTaskRunner implements LayerCommitting {

    private static final Logger LOG = Logger.getLogger(EntityTaskRunner.class.getName());

    private final EntityPhysicsState state;
    private final Function<String, EntityTaskAction> actionResolver;
    private final EntityAccessTracer tracer;
    private final EntityTaskGuardHook guardHook;
    private final LayeredRandomSource randomSource;
    private final RandomBudget randomBudget;
    private final ConcurrentHashMap<String, EntityStateSnapshot> layerSnapshots = new ConcurrentHashMap<>();
    private volatile long currentTick;
    private volatile TerrainView terrain = TerrainView.EMPTY;

    /**
     * Previous-tick snapshot of AI-perception fields, populated at the end of
     * every {@link #commitLayer()}. Under {@link FidelityTier#T2} relaxed
     * determinism the AI SENSE stage reads from this map instead of the live
     * CAS store — a one-tick freshness lag accepted for throughput.
     */
    private final Map<EntityField, Object> previousTickSnapshot = new ConcurrentHashMap<>();

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver) {
        this(state, actionResolver, null, null, null, null);
    }

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver,
                            LayeredRandomSource randomSource) {
        this(state, actionResolver, null, null, randomSource, null);
    }

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver,
                            LayeredRandomSource randomSource, RandomBudget randomBudget) {
        this(state, actionResolver, null, null, randomSource, randomBudget);
    }

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver,
                            EntityAccessTracer tracer, EntityTaskGuardHook guardHook) {
        this(state, actionResolver, tracer, guardHook, null, null);
    }

    public EntityTaskRunner(EntityPhysicsState state, Function<String, EntityTaskAction> actionResolver,
                            EntityAccessTracer tracer, EntityTaskGuardHook guardHook,
                            LayeredRandomSource randomSource, RandomBudget randomBudget) {
        this.state = state;
        this.actionResolver = actionResolver != null ? actionResolver : id -> null;
        this.tracer = tracer;
        this.guardHook = guardHook;
        this.randomSource = randomSource;
        this.randomBudget = randomBudget;
    }

    /**
     * Sets the read-only terrain oracle used by collision-aware actions. Terrain
     * is constant for a tick; returns {@code this} for chaining.
     */
    public EntityTaskRunner withTerrain(TerrainView terrain) {
        this.terrain = terrain == null ? TerrainView.EMPTY : terrain;
        return this;
    }

    /** Sets the tick coordinate for RNG seeds and resets per-tick budget stats. */
    public void beginTick(long tick) {
        this.currentTick = tick;
        if (randomBudget != null) {
            randomBudget.beginTick();
        }
    }

    @Override
    public void run(TaskNode task) throws Exception {
        if (guardHook != null) {
            guardHook.beforeTask(task);
        }
        try {
            dispatch(task);
        } finally {
            if (guardHook != null) {
                guardHook.afterTask(task);
            }
        }
    }

    private void dispatch(TaskNode task) throws Exception {
        if (CompoundTask.isCompound(task)) {
            runCompound(task);
            return;
        }
        // Extract the declared RNG estimate so the budget tracker only counts
        // tasks that actually declare RandomUsage (others would dilute the rate).
        Integer estimate = task.declaredRWSet().randomUsage()
            .filter(u -> u.instance() != RandomInstance.NONE)
            .map(RandomUsage::maxCallsEstimate)
            .orElse(null);
        runMember(task.taskId(), estimate);
    }

    private void runCompound(TaskNode compound) throws Exception {
        List<String> memberIds = new ArrayList<>(CompoundTask.memberIds(compound));
        memberIds.sort(DeterministicOrdering::compareTaskIds);
        for (String memberId : memberIds) {
            // Per-member declared estimates are not recoverable from the
            // compound ID; compound members (e.g. collision pairs) are not
            // RNG-declaring in practice, so they are not budget-tracked.
            runMember(memberId, null);
        }
    }

    private void runMember(String taskId, Integer randomEstimate) throws Exception {
        EntityTaskAction action = actionResolver.apply(taskId);
        if (action == null) {
            return; // pure-read or unmodelled task — no state mutation
        }
        EntityStateSnapshot snapshot = new EntityStateSnapshot();
        DeterministicRandom rng = null;
        long entityId = 0L;
        if (randomSource != null) {
            entityId = parseEntityId(taskId);
            rng = randomSource.forTask(currentTick, entityId, RandomInstance.ENTITY_RANDOM);
        }
        action.execute(new EntityTaskContext(state, snapshot, rng, terrain, tracer));

        // Evaluate RNG consumption against the allocated budget (DG2 metric).
        if (rng != null && randomBudget != null && randomEstimate != null) {
            int allocated = randomBudget.allocate(entityId, randomEstimate);
            randomBudget.evaluate(entityId, allocated, rng.callsMade());
        }

        if (!snapshot.isEmpty()) {
            layerSnapshots.put(taskId, snapshot);
        }
    }

    /**
     * Parses the primary entity ID from an entity task ID. Formats:
     * {@code TYPE@dim:entityId:...} (single) or {@code TYPE@dim:lo,hi} (pair) —
     * for a pair we use the lower ID so the seed is order-stable. Returns 0 if
     * the ID cannot be parsed (RNG still deterministic, just shares a stream).
     */
    static long parseEntityId(String taskId) {
        int at = taskId.indexOf('@');
        if (at < 0) return 0L;
        int colon = taskId.indexOf(':', at + 1);
        if (colon < 0) return 0L;
        String rest = taskId.substring(colon + 1);
        // Take the first numeric token, stopping at ':' or ',' separators.
        int end = 0;
        while (end < rest.length()) {
            char c = rest.charAt(end);
            if (c == ':' || c == ',') break;
            end++;
        }
        try {
            return Long.parseLong(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Commits all snapshots accumulated in the current layer.
     *
     * @return task IDs whose commits failed (stale reads); empty if all succeeded
     */
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            EntityStateSnapshot snapshot = entry.getValue();
            if (snapshot.isEmpty()) continue;
            EntityStateSnapshot.CommitResult result = snapshot.commit(state);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for entity task " + entry.getKey()
                    + " at fields: " + result.failedFields().keySet());
            }
        }
        captureAiSnapshotForStaleRead();
        return failed;
    }

    /**
     * Captures a per-tick stale snapshot of AI-perception fields from the live
     * CAS store. Called at the end of {@link #commitLayer()} so the next tick's
     * SENSE action can read it under {@link FidelityTier#T2}. Only fields
     * prefixed {@code ai_state.} or named {@code position_snapshot} are kept —
     * position/health are NOT included because they're written every tick and
     * stale-position reads would visibly desync AI movement.
     */
    private void captureAiSnapshotForStaleRead() {
        Map<EntityField, Object> next = new LinkedHashMap<>();
        for (EntityField field : state.fields()) {
            String name = field.fieldPath().value();
            if (name.startsWith("ai_state.") || "position_snapshot".equals(name)) {
                EntityPhysicsState.VersionedEntry entry =
                    state.peek(field);
                if (entry != null) {
                    next.put(field, entry.value());
                }
            }
        }
        previousTickSnapshot.clear();
        previousTickSnapshot.putAll(next);
    }

    /**
     * Returns the stale (previous-tick) snapshot for an AI-perception field, or
     * {@code null} if the field was not captured (caller should fall back to a
     * live read).
     */
    public Object readPreviousTickSnapshot(EntityField field) {
        return previousTickSnapshot.get(field);
    }

    /** Returns true when the active tier permits stale (previous-tick) AI reads. */
    public boolean useStaleAiSnapshot() {
        return FidelityTier.currentTier().useStaleAiSnapshot();
    }

    public void resetLayer() {
        layerSnapshots.clear();
    }

    public EntityPhysicsState state() {
        return state;
    }

    /**
     * Fraction of RNG-declaring entities that exceeded their allocated budget
     * in the current tick (DG2 metric; arch doc §11.2 target &lt;1%). Returns 0
     * if no budget tracker is configured.
     */
    public double currentOverBudgetRate() {
        return randomBudget == null ? 0.0 : randomBudget.currentOverBudgetRate();
    }

    public RandomBudget randomBudget() {
        return randomBudget;
    }
}
