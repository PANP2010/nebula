package org.nebula.folia.bridge;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Intercepts Folia's CollectingNeighborUpdater to feed block-update events
 * into the Nebula DAG microstep system (arch doc §2.3).
 *
 * <p>In Folia, every block state change produces a
 * {@code CollectingNeighborUpdater} record that propagates BLOCK_UPDATE
 * to neighbouring positions.  Nebula needs to intercept this fan-out point
 * to:
 * <ol>
 *   <li>Record which positions received BLOCK_UPDATE events (input to
 *       {@link org.nebula.core.scheduler.MicroStepExtender#extend}).</li>
 *   <li>Create new {@link TaskNode}s for affected redstone components via the
 *       registered {@link BlockUpdateListener}s.</li>
 *   <li>Optionally suppress Folia's own propagation for positions handled
 *       by the Nebula DAG executor (controlled by {@link #setActive}).</li>
 * </ol>
 *
 * <h3>Integration mode</h3>
 * In {@code OBSERVE} mode (default), Nebula listens passively — Folia still
 * executes its own updates.  This is safe for Phase 0 testing without
 * replacing Folia's execution path.<br>
 * In {@code INTERCEPT} mode (Phase 0 validation), Nebula suppresses Folia's
 * propagation and drives it through the DAG instead.  Only enable once the
 * DAG execution path has been verified deterministic.
 */
public final class NeighborUpdateInterceptor {

    public enum Mode {
        /** Observe updates passively — Folia still executes normally. */
        OBSERVE,
        /** Intercept updates — Nebula drives propagation via DAG. */
        INTERCEPT
    }

    /**
     * Called when a BLOCK_UPDATE event is about to be dispatched to a
     * neighbour position.
     */
    @FunctionalInterface
    public interface BlockUpdateListener {
        /**
         * @param worldName  dimension name (e.g. "minecraft:overworld")
         * @param x          block x coordinate
         * @param y          block y coordinate
         * @param z          block z coordinate
         * @return {@code true} to suppress Folia's propagation (INTERCEPT mode),
         *         {@code false} to allow it to continue (OBSERVE mode)
         */
        boolean onBlockUpdate(String worldName, int x, int y, int z);
    }

    private static volatile Mode currentMode = Mode.OBSERVE;
    private static volatile boolean active = false;
    private static final List<BlockUpdateListener> listeners = new CopyOnWriteArrayList<>();

    private NeighborUpdateInterceptor() {}

    // ── Configuration ─────────────────────────────────────────────────────────

    public static void setMode(Mode mode) {
        currentMode = mode;
    }

    public static Mode getMode() {
        return currentMode;
    }

    /** Enables interception. Call from NebulaFoliaBootstrap.activate(). */
    public static void setActive(boolean active) {
        NeighborUpdateInterceptor.active = active;
    }

    public static boolean isActive() {
        return active;
    }

    /** Registers a listener for BLOCK_UPDATE events. */
    public static void addListener(BlockUpdateListener listener) {
        listeners.add(listener);
    }

    public static void removeListener(BlockUpdateListener listener) {
        listeners.remove(listener);
    }

    // ── Hook (called by the JVM Agent instrumentation) ────────────────────────

    /**
     * Entry point called by the instrumented {@code CollectingNeighborUpdater}
     * before it dispatches a block update.
     *
     * <p>This method is invoked via {@code TraceHooks} by the
     * {@code AccessTracingTransformer} bytecode instrumentation.  It must be
     * fast and must not allocate on the hot path.
     *
     * @param worldName  the dimension name string
     * @param x          target block x
     * @param y          target block y
     * @param z          target block z
     * @return {@code true} if Folia's propagation should be suppressed
     */
    public static boolean onNeighborUpdate(String worldName, int x, int y, int z) {
        if (!active || listeners.isEmpty()) {
            return false; // passthrough
        }
        boolean suppress = false;
        for (BlockUpdateListener listener : listeners) {
            if (listener.onBlockUpdate(worldName, x, y, z)) {
                suppress = true;
            }
        }
        return suppress && currentMode == Mode.INTERCEPT;
    }

    /**
     * Convenience: fire a synthetic block update from a task's write set.
     * Used by {@link org.nebula.core.scheduler.MicroStepExtender} to replay
     * updates through the listener chain after DAG execution.
     */
    public static void fireFromTask(TaskNode task, String worldName) {
        for (WorldPos pos : task.declaredRWSet().writtenBlocks()) {
            onNeighborUpdate(worldName, pos.x(), pos.y(), pos.z());
        }
    }
}
