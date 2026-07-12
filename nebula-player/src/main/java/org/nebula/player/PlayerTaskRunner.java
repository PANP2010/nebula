package org.nebula.player;

import org.nebula.core.player.PlayerPhysicsState;
import org.nebula.core.player.PlayerStateSnapshot;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link TaskRunner} that executes player tasks against a shared
 * {@link PlayerPhysicsState}. Mirrors {@link org.nebula.entity.EntityTaskRunner}.
 *
 * <p>Each task gets its own {@link PlayerStateSnapshot}; after a layer completes
 * the caller invokes {@link #commitLayer()} to CAS-commit all pending writes,
 * retrying stale-read losers.
 *
 * <p>If constructed with a {@link LayeredRandomSource}, each task receives a
 * {@link DeterministicRandom} seeded from {@code (tick, playerUUID, instance)}.
 */
public final class PlayerTaskRunner implements LayerCommitting {

    private static final Logger LOG = Logger.getLogger(PlayerTaskRunner.class.getName());

    private final PlayerPhysicsState state;
    private final Function<String, PlayerTaskAction> actionResolver;
    private final PlayerAccessTracer tracer;
    private final LayeredRandomSource randomSource;
    private final RandomBudget randomBudget;
    private final ConcurrentHashMap<String, PlayerStateSnapshot> layerSnapshots = new ConcurrentHashMap<>();
    private volatile long currentTick;

    public PlayerTaskRunner(PlayerPhysicsState state,
                            Function<String, PlayerTaskAction> actionResolver) {
        this(state, actionResolver, null, null, null);
    }

    public PlayerTaskRunner(PlayerPhysicsState state,
                            Function<String, PlayerTaskAction> actionResolver,
                            LayeredRandomSource randomSource) {
        this(state, actionResolver, null, randomSource, null);
    }

    public PlayerTaskRunner(PlayerPhysicsState state,
                            Function<String, PlayerTaskAction> actionResolver,
                            LayeredRandomSource randomSource,
                            RandomBudget randomBudget) {
        this(state, actionResolver, null, randomSource, randomBudget);
    }

    public PlayerTaskRunner(PlayerPhysicsState state,
                            Function<String, PlayerTaskAction> actionResolver,
                            PlayerAccessTracer tracer,
                            LayeredRandomSource randomSource,
                            RandomBudget randomBudget) {
        this.state = state;
        this.actionResolver = actionResolver != null ? actionResolver : id -> null;
        this.tracer = tracer;
        this.randomSource = randomSource;
        this.randomBudget = randomBudget;
    }

    public void beginTick(long tick) {
        this.currentTick = tick;
        if (randomBudget != null) {
            randomBudget.beginTick();
        }
    }

    @Override
    public void run(TaskNode task) throws Exception {
        dispatch(task);
    }

    private void dispatch(TaskNode task) throws Exception {
        if (CompoundTask.isCompound(task)) {
            runCompound(task);
            return;
        }
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
            runMember(memberId, null);
        }
    }

    private void runMember(String taskId, Integer randomEstimate) throws Exception {
        PlayerTaskAction action = actionResolver.apply(taskId);
        if (action == null) {
            return;
        }
        PlayerStateSnapshot snap = new PlayerStateSnapshot();
        DeterministicRandom rng = null;
        long uuidBits = 0L;
        if (randomSource != null) {
            uuidBits = parseUuidBits(taskId);
            rng = randomSource.forTask(currentTick, uuidBits, RandomInstance.PLAYER_RANDOM);
        }
        action.execute(new PlayerTaskContext(state, snap, rng, null, tracer));

        if (rng != null && randomBudget != null && randomEstimate != null) {
            int allocated = randomBudget.allocate(uuidBits, randomEstimate);
            randomBudget.evaluate(uuidBits, allocated, rng.callsMade());
        }

        if (!snap.isEmpty()) {
            layerSnapshots.put(taskId, snap);
        }
    }

    static long parseUuidBits(String taskId) {
        int at = taskId.indexOf('@');
        if (at < 0) return 0L;
        int colon = taskId.indexOf(':', at + 1);
        if (colon < 0) return 0L;
        String uuidStr = taskId.substring(at + 1, colon);
        try {
            return java.util.UUID.fromString(uuidStr).getLeastSignificantBits();
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    @Override
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (var entry : layerSnapshots.entrySet()) {
            PlayerStateSnapshot snap = entry.getValue();
            if (snap.isEmpty()) continue;
            PlayerStateSnapshot.CommitResult result = snap.commit(state);
            if (!result.success()) {
                failed.add(entry.getKey());
                LOG.warning(() -> "CAS commit failed for player task " + entry.getKey()
                    + " at fields: " + result.failedFields().keySet());
            }
        }
        return failed;
    }

    @Override
    public void resetLayer() {
        layerSnapshots.clear();
    }

    public PlayerPhysicsState state() {
        return state;
    }

    public double currentOverBudgetRate() {
        return randomBudget == null ? 0.0 : randomBudget.currentOverBudgetRate();
    }
}
