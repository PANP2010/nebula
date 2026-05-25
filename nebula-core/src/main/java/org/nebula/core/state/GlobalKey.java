package org.nebula.core.state;

import java.util.Objects;

public record GlobalKey(String value) implements Comparable<GlobalKey> {

    /** Wildcard — conflicts with every other key. */
    public static final GlobalKey ALL = new GlobalKey("*");

    // ── Vanilla global state (arch doc §1.1 G-tuple) ─────────────────────────

    /** Current game tick counter (total ticks since world creation). */
    public static final GlobalKey GAME_TIME = new GlobalKey("game_time");

    /** Time of day (0–23999). */
    public static final GlobalKey DAY_TIME = new GlobalKey("day_time");

    /** Weather state (clear / rain / thunder + transition counters). */
    public static final GlobalKey WEATHER = new GlobalKey("weather");

    /** World border (center + radius). */
    public static final GlobalKey WORLD_BORDER = new GlobalKey("world_border");

    /** Difficulty (peaceful/easy/normal/hard). */
    public static final GlobalKey DIFFICULTY = new GlobalKey("difficulty");

    /** All game rules map. */
    public static final GlobalKey GAME_RULES = new GlobalKey("game_rules");

    /** Server player list and connection state. */
    public static final GlobalKey PLAYER_LIST = new GlobalKey("player_list");

    // ── Folia RegionizedWorldData — per-region redstone state ────────────────

    /**
     * Folia's per-region flag that prevents infinite recursion during
     * {@code getBestNeighborSignal} calls inside redstone wire signal
     * propagation.  Read/written by {@code RedStoneWireBlock.getBlockSignal},
     * {@code getDirectSignal}, {@code getSignal}, and
     * {@code RedstoneWireTurbo} at the top of each wire-update pass.
     *
     * <p>Any task that touches wire signal strength must declare this key
     * in its write set (it temporarily sets it to {@code false}).
     */
    public static final GlobalKey REGION_SHOULD_SIGNAL =
        new GlobalKey("region.should_signal");

    /**
     * Folia's per-region deque of {@code RedstoneTorchBlock.Toggle} records,
     * used to detect rapid-clock burnout (> 8 state changes in 60 ticks).
     * Read/written by {@code RedstoneTorchBlock.tick} and
     * {@code isToggledTooFrequently}.
     */
    public static final GlobalKey REGION_REDSTONE_TORCH_TOGGLES =
        new GlobalKey("region.redstone_torch_toggles");

    /**
     * Folia's per-region {@code RedstoneWireTurbo} instance (Eigencraft
     * Paper optimised wire algorithm).  Written during world initialisation,
     * read by every redstone wire update.
     */
    public static final GlobalKey REGION_WIRE_TURBO =
        new GlobalKey("region.wire_turbo");

    /**
     * Folia's per-region {@code WireHandler} instance (Alternate-Current
     * algorithm, if enabled).  Parallel to REGION_WIRE_TURBO.
     */
    public static final GlobalKey REGION_WIRE_HANDLER =
        new GlobalKey("region.wire_handler");

    /**
     * Per-region game time used by redstone torch burnout detection.
     * {@code level.getRedstoneGameTime()} instead of the global
     * {@link #GAME_TIME}, so each region can have its own clock.
     */
    public static final GlobalKey REGION_REDSTONE_GAME_TIME =
        new GlobalKey("region.redstone_game_time");

    // ── Folia RegionizedWorldData — per-region tick scheduling ───────────────

    /**
     * Per-region block-level scheduled ticks queue ({@code blockLevelTicks}).
     * Repeater and comparator delays are scheduled here.
     */
    public static final GlobalKey REGION_BLOCK_LEVEL_TICKS =
        new GlobalKey("region.block_level_ticks");

    /**
     * Per-region fluid-level scheduled ticks queue ({@code fluidLevelTicks}).
     */
    public static final GlobalKey REGION_FLUID_LEVEL_TICKS =
        new GlobalKey("region.fluid_level_ticks");

    /**
     * Per-region {@code CollectingNeighborUpdater} — the queue of pending
     * neighbor update records (block update fan-out).  Written whenever a
     * block state changes and BLOCK_UPDATE events are fired.
     */
    public static final GlobalKey REGION_NEIGHBOR_UPDATER =
        new GlobalKey("region.neighbor_updater");

    // ── Spawn / natural spawning ──────────────────────────────────────────────

    /** Natural mob spawner state (NaturalSpawner.createState result). */
    public static final GlobalKey SPAWN_STATE = new GlobalKey("spawn_state");

    // ─────────────────────────────────────────────────────────────────────────

    public GlobalKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("global key must not be blank");
        }
    }

    public boolean conflictsWith(GlobalKey other) {
        return ALL.equals(this) || ALL.equals(other) || value.equals(other.value);
    }

    @Override
    public int compareTo(GlobalKey other) {
        return value.compareTo(other.value);
    }
}
