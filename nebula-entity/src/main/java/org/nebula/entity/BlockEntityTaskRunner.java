package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.random.RandomBudget;
import org.nebula.core.scheduler.CompoundTask;
import org.nebula.core.scheduler.DeterministicOrdering;
import org.nebula.core.scheduler.LayerCommitting;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link LayerCommitting} runner for block-entity tick tasks, executing
 * against a shared {@link BlockEntityState}. Mirrors {@code EntityTaskRunner} /
 * {@code RedstoneTaskRunner}.
 *
 * <p>Each task gets its own {@link BlockEntitySnapshotState}; after a layer
 * completes, {@link #commitLayer()} CAS-commits all buffered writes and returns
 * the stale-read losers. SCC-contracted compounds (e.g. a chain of hoppers
 * feeding each other) dispatch each member through the resolver in deterministic
 * order, so live behaviour survives contraction.
 *
 * <p>If constructed with a {@link LayeredRandomSource}, each RNG-declaring task
 * receives a {@link DeterministicRandom} seeded from
 * {@code (tick, blockPosKey, instance)} — where {@code blockPosKey} is the
 * task's block position packed via {@link #parseBlockPosKey} and {@code instance}
 * is read from the task's own declared {@link RandomUsage} (WORLD_RANDOM for the
 * dropper/dispenser slot draw). So the RNG stream depends only on the block's
 * coordinate, never on execution order — the precondition for parallelising
 * RNG-consuming block-entity ticks, mirroring {@code EntityTaskRunner}. The
 * caller sets the current tick via {@link #beginTick(long)}. Tasks that declare
 * no RNG (hopper/furnace) get no random source and running them untouched.
 *
 * <p>If additionally given a {@link RandomBudget}, the runner allocates a budget
 * per RNG-declaring task from its declared estimate and evaluates actual
 * {@code callsMade()} against it after execution (the DG2 over-budget metric),
 * queryable via {@link #currentOverBudgetRate()} — again mirroring the entity
 * runner.
 */
public final class BlockEntityTaskRunner implements LayerCommitting {

    private static final Logger LOG = Logger.getLogger(BlockEntityTaskRunner.class.getName());

    private final BlockEntityState state;
    private final Function<String, BlockEntityAction> actionResolver;
    private final ConcurrentHashMap<String, BlockEntitySnapshotState> layerSnapshots = new ConcurrentHashMap<>();
    private final BlockEntityAccessTracer tracer;
    private final BlockEntityTaskGuardHook guardHook;
    private final LayeredRandomSource randomSource;
    private final RandomBudget randomBudget;
    private volatile long currentTick;

    public BlockEntityTaskRunner(BlockEntityState state, Function<String, BlockEntityAction> actionResolver) {
        this(state, actionResolver, null, null);
    }

    /**
     * @param tracer    optional per-access hook feeding the RW-guard's thread-local
     *                  trace (see {@link BlockEntityAccessTracer}); installed on each
     *                  task's {@link BlockEntityContext} so every field read/write the
     *                  action performs is observed. Null → the context runs untraced.
     * @param guardHook optional per-task hook (see {@link BlockEntityTaskGuardHook})
     *                  that brackets each dispatched task's execution so a guard can
     *                  reset the trace before it runs and check the snapshot after.
     *                  Null → the runner behaves exactly as before.
     */
    public BlockEntityTaskRunner(BlockEntityState state, Function<String, BlockEntityAction> actionResolver,
                                 BlockEntityAccessTracer tracer, BlockEntityTaskGuardHook guardHook) {
        this(state, actionResolver, tracer, guardHook, null, null);
    }

    /**
     * Full constructor: adds a {@link LayeredRandomSource} (so RNG-declaring tasks
     * get a deterministic per-block stream) and an optional {@link RandomBudget}
     * (so their draw counts are tracked against the DG2 over-budget metric). Both
     * may be null — a null random source leaves {@link BlockEntityContext#random()}
     * throwing, exactly as before, which is correct for the hopper/furnace path
     * that consumes no RNG.
     */
    public BlockEntityTaskRunner(BlockEntityState state, Function<String, BlockEntityAction> actionResolver,
                                 BlockEntityAccessTracer tracer, BlockEntityTaskGuardHook guardHook,
                                 LayeredRandomSource randomSource, RandomBudget randomBudget) {
        this.state = state;
        this.actionResolver = actionResolver != null ? actionResolver : id -> null;
        this.tracer = tracer;
        this.guardHook = guardHook;
        this.randomSource = randomSource;
        this.randomBudget = randomBudget;
    }

    /** Sets the tick coordinate for RNG seeds and resets per-tick budget stats. */
    public void beginTick(long tick) {
        this.currentTick = tick;
        if (randomBudget != null) {
            randomBudget.beginTick();
        }
    }

    /**
     * Builds a runner whose action resolver is the canonical
     * {@code taskId → snapshot → action} composition (B8 C3).
     *
     * <p>Why this composition needs a snapshot lookup, not just the task ID: a
     * block-entity {@code taskId} is {@code TYPE@dim:x,y,z} — it carries the type and
     * position but <em>not</em> the hopper's facing/output direction or slot count,
     * all of which {@link BlockEntityActionResolver#resolve} needs (see that class's
     * javadoc). So unlike the entity runner (which re-decodes an ENTITY_MOVE action
     * straight from its ID), the block-entity action must be resolved from the
     * {@link BlockEntitySnapshot} the tick hook already accumulated. This factory keeps
     * that composition in ONE place so the live plugin wiring and the unit tests share
     * it — a diverging copy would be exactly the silent-mismatch wound (B3) this
     * subsystem keeps re-learning.
     *
     * @param state       the shared CAS store
     * @param snapshotById maps a live task ID back to the snapshot that seeded it
     *                     (the plugin backs this with the per-tick snapshot registry
     *                     the hook's {@code TaskResolver} populates); a {@code null}
     *                     lookup result resolves to a no-op action
     */
    public static BlockEntityTaskRunner withSnapshotResolver(
            BlockEntityState state, Function<String, BlockEntitySnapshot> snapshotById) {
        return withSnapshotResolver(state, snapshotById, null, null, null, null);
    }

    /**
     * Guard-aware variant of {@link #withSnapshotResolver(BlockEntityState, Function)}:
     * builds the same canonical {@code taskId → snapshot → action} resolver but also
     * installs the RW-guard seams so a live guard bridge can verify each task's actual
     * field accesses against its declared {@code RWSet}. Keeping BOTH factories on the
     * same resolver composition is deliberate — a diverging copy of the snapshot lookup
     * would be the silent-mismatch wound (B3) this subsystem keeps re-learning.
     *
     * @param tracer    per-access hook feeding the guard's thread-local trace (installed
     *                  on each task's {@link BlockEntityContext}); null → untraced
     * @param guardHook per-task bracket the guard resets/snapshots the trace around;
     *                  null → the runner brackets nothing
     */
    public static BlockEntityTaskRunner withSnapshotResolver(
            BlockEntityState state, Function<String, BlockEntitySnapshot> snapshotById,
            BlockEntityAccessTracer tracer, BlockEntityTaskGuardHook guardHook) {
        return withSnapshotResolver(state, snapshotById, tracer, guardHook, null, null);
    }

    /**
     * RNG-aware variant of the canonical snapshot resolver: builds the same
     * {@code taskId → snapshot → action} composition but threads a
     * {@link LayeredRandomSource} + optional {@link RandomBudget} into the runner, so
     * an RNG-declaring block entity (dropper/dispenser) resolved on a region thread
     * gets a deterministic per-block stream instead of {@link BlockEntityContext#random()}
     * throwing. Keeping ALL snapshot-resolver factories on this one composition is
     * deliberate — a diverging copy of the lookup is the silent-mismatch wound (B3)
     * this subsystem keeps re-learning.
     *
     * <p>Both {@code randomSource} and {@code randomBudget} may be null (the hopper/furnace
     * path consumes no RNG, so a null source correctly leaves {@code random()} throwing).
     *
     * @param randomSource seeds each RNG-declaring task from {@code (tick, blockPos, instance)};
     *                     null → no stream (correct for the RNG-free hopper/furnace path)
     * @param randomBudget optional DG2 over-budget tracker for RNG-declaring tasks; null → untracked
     */
    public static BlockEntityTaskRunner withSnapshotResolver(
            BlockEntityState state, Function<String, BlockEntitySnapshot> snapshotById,
            BlockEntityAccessTracer tracer, BlockEntityTaskGuardHook guardHook,
            LayeredRandomSource randomSource, RandomBudget randomBudget) {
        Function<String, BlockEntitySnapshot> lookup =
            snapshotById != null ? snapshotById : id -> null;
        return new BlockEntityTaskRunner(state,
            taskId -> BlockEntityActionResolver.resolve(lookup.apply(taskId)),
            tracer, guardHook, randomSource, randomBudget);
    }

    @Override
    public void run(TaskNode task) throws Exception {
        // The guard hook brackets the WHOLE dispatched task — including a compound's
        // members — so the accesses it observes match the declared RW-set it checks
        // against (a compound's merged set covers the union of its members' accesses).
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
            List<String> memberIds = new ArrayList<>(CompoundTask.memberIds(task));
            memberIds.sort(DeterministicOrdering::compareTaskIds);
            for (String memberId : memberIds) {
                // Per-member declared RandomUsage is not recoverable from the compound
                // ID; a compound of RNG-declaring block entities is not a case that
                // arises today (droppers/dispensers fire on disjoint redstone edges, so
                // they do not SCC-contract into one another), so members run RNG-free.
                runMember(memberId, RandomInstance.NONE, null);
            }
            return;
        }
        // A single task carries its own declared RW-set, so we can key the RNG off
        // its declared RandomUsage instance and estimate — the WORLD_RANDOM stream
        // the dropper/dispenser slot draw needs.
        RandomUsage usage = task.declaredRWSet().randomUsage()
            .filter(u -> u.instance() != RandomInstance.NONE)
            .orElse(null);
        RandomInstance instance = usage != null ? usage.instance() : RandomInstance.NONE;
        Integer estimate = usage != null ? usage.maxCallsEstimate() : null;
        runMember(task.taskId(), instance, estimate);
    }

    private void runMember(String taskId, RandomInstance instance, Integer randomEstimate) throws Exception {
        BlockEntityAction action = actionResolver.apply(taskId);
        if (action == null) {
            return;
        }
        BlockEntitySnapshotState snapshot = new BlockEntitySnapshotState();
        DeterministicRandom rng = null;
        long posKey = 0L;
        if (randomSource != null && instance != RandomInstance.NONE) {
            posKey = parseBlockPosKey(taskId);
            rng = randomSource.forTask(currentTick, posKey, instance);
        }
        action.execute(new BlockEntityContext(state, snapshot, tracer, rng));

        // Evaluate RNG consumption against the allocated budget (DG2 metric), keyed by
        // the block position so two block entities don't share a budget bucket.
        if (rng != null && randomBudget != null && randomEstimate != null) {
            int allocated = randomBudget.allocate(posKey, randomEstimate);
            randomBudget.evaluate(posKey, allocated, rng.callsMade());
        }

        if (!snapshot.isEmpty()) {
            layerSnapshots.put(taskId, snapshot);
        }
    }

    /**
     * Packs the block position out of a block-entity task ID ({@code TYPE@dim:x,y,z})
     * into a stable 64-bit RNG-seed key. Two block entities at different positions
     * get uncorrelated streams; the same position on the same tick always derives the
     * same seed regardless of execution order. Returns 0 if the ID cannot be parsed
     * (RNG stays deterministic, just shares a stream — the entity runner's fallback).
     *
     * <p>Packs 21 bits each of x/y/z (Minecraft's {@code BlockPos.asLong} layout,
     * enough for the ±30M world border) plus the low bits of the dimension, so the
     * key is collision-free across the playable coordinate range.
     */
    static long parseBlockPosKey(String taskId) {
        int at = taskId.indexOf('@');
        if (at < 0) return 0L;
        int colon = taskId.indexOf(':', at + 1);
        if (colon < 0) return 0L;
        String rest = taskId.substring(colon + 1);
        String[] coords = rest.split(",");
        if (coords.length < 3) return 0L;
        try {
            long dim = Long.parseLong(taskId.substring(at + 1, colon).trim());
            long x = Long.parseLong(coords[0].trim());
            long y = Long.parseLong(coords[1].trim());
            long z = Long.parseLong(coords[2].trim());
            long key = ((x & 0x1FFFFFL) << 43) | ((z & 0x1FFFFFL) << 22) | (y & 0x3FFFFFL);
            return key ^ (dim << 1);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            BlockEntitySnapshotState snapshot = entry.getValue();
            if (snapshot.isEmpty()) continue;
            BlockEntitySnapshotState.CommitResult result = snapshot.commit(state);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for block-entity task " + entry.getKey()
                    + " at fields: " + result.failedFields().keySet());
            }
        }
        return failed;
    }

    @Override
    public void resetLayer() {
        layerSnapshots.clear();
    }

    public BlockEntityState state() {
        return state;
    }

    /**
     * Fraction of RNG-declaring block entities that exceeded their allocated budget
     * in the current tick (DG2 metric; arch doc §11.2 target &lt;1%). Returns 0 if no
     * budget tracker is configured.
     */
    public double currentOverBudgetRate() {
        return randomBudget == null ? 0.0 : randomBudget.currentOverBudgetRate();
    }

    public RandomBudget randomBudget() {
        return randomBudget;
    }
}
