package org.nebula.redstone;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

/**
 * Enumerates redstone component types with their static metadata.
 * Each type declares its RW-set shape, microstep behaviour, and SCC policy
 * — enough to instantiate {@link org.nebula.core.scheduler.TaskNode}s via
 * {@link RedstoneTaskFactory}.
 */
public enum RedstoneComponentType {

    /** 6-directional signal propagation, always propagates within the tick. */
    REDSTONE_WIRE(
        "REDSTONE_WIRE",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /**
     * Delayed output (1–4 ticks). Output changes are deferred to a future tick,
     * so it never triggers new tasks in the current microstep.
     */
    REPEATER(
        "REPEATER",
        MicroStepBehavior.DEFERRED,
        SccBehavior.CONTRACTIBLE),

    /** Compares / subtracts input signals; output propagates within the tick. */
    COMPARATOR(
        "COMPARATOR",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Inverts the attached block's power state; propagates within the tick. */
    REDSTONE_TORCH(
        "REDSTONE_TORCH",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Piston extension/retraction; output is deferred (movement resolves next tick). */
    PISTON(
        "PISTON",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /** Sticky piston: same as piston plus may push/pull a block. */
    STICKY_PISTON(
        "STICKY_PISTON",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /** Observer: detects block updates behind it, emits a short pulse forward. */
    OBSERVER(
        "OBSERVER",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Note block: plays a note on activation. */
    NOTE_BLOCK(
        "NOTE_BLOCK",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Trapdoor / fence gate / iron door powered by redstone. */
    POWERED_RAIL(
        "POWERED_RAIL",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    // ── New types (Phase 1C) ─────────────────────────────────────────────────

    /** Tripwire hook: detects string connection, fires BLOCK_UPDATE on trigger. */
    TRIPWIRE_HOOK(
        "TRIPWIRE_HOOK",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Tripwire string: connects hooks, propagates connection state. */
    TRIPWIRE(
        "TRIPWIRE",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Redstone lamp: turns on/off based on received power; no propagation. */
    REDSTONE_LAMP(
        "REDSTONE_LAMP",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Daylight detector: emits signal based on sky light; propagates within tick. */
    DAYLIGHT_DETECTOR(
        "DAYLIGHT_DETECTOR",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE),

    /** Hopper: item transfer is deferred; comparator output may propagate. */
    HOPPER(
        "HOPPER",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /** Dispenser: fires on redstone pulse, item ejection is deferred. */
    DISPENSER(
        "DISPENSER",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /** Dropper: drops item on redstone pulse, deferred. */
    DROPPER(
        "DROPPER",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /** TNT: primed on redstone signal; explosion deferred (sub-DAG next layers). */
    TNT(
        "TNT",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Activator rail: activates minecarts passing over; no further propagation. */
    ACTIVATOR_RAIL(
        "ACTIVATOR_RAIL",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Redstone block: constant power source; no state change, no microstep. */
    REDSTONE_BLOCK(
        "REDSTONE_BLOCK",
        MicroStepBehavior.NONE,
        SccBehavior.CONTRACTIBLE),

    /** Lever: manual toggle; propagates signal to adjacent blocks. */
    LEVER(
        "LEVER",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Button: short pulse on press; propagates once, then off. */
    BUTTON(
        "BUTTON",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.AUTO),

    /** Pressure plate: active while entity is on it; propagates signal. */
    PRESSURE_PLATE(
        "PRESSURE_PLATE",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.AUTO),

    /** Fence gate: opens/closes; no signal propagation. */
    FENCE_GATE(
        "FENCE_GATE",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Trapdoor: opens/closes on power; no signal propagation. */
    TRAPDOOR(
        "TRAPDOOR",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Iron door: opens/closes on power; no signal propagation. */
    IRON_DOOR(
        "IRON_DOOR",
        MicroStepBehavior.NONE,
        SccBehavior.AUTO),

    /** Piston head (extension block): created by piston, removed on retraction; deferred. */
    PISTON_HEAD(
        "PISTON_HEAD",
        MicroStepBehavior.DEFERRED,
        SccBehavior.SERIALIZED),

    /**
     * Detector rail: a signal source that powers ON while a minecart sits in its
     * search AABB and schedules a 20-tick release check. A press ripples power to
     * connected rails and fans neighbour updates to both {@code pos} and
     * {@code pos.below}, so it propagates within the tick; the release is deferred
     * via the scheduled tick. Verified against DetectorRailBlock.checkPressed
     * (mojmaps 1.21.x): {@code isSignalSource == true}, {@code getSignal == 15} when powered.
     */
    DETECTOR_RAIL(
        "DETECTOR_RAIL",
        MicroStepBehavior.PROPAGATES,
        SccBehavior.CONTRACTIBLE);

    private final String taskType;
    private final MicroStepBehavior microStep;
    private final SccBehavior scc;

    RedstoneComponentType(String taskType, MicroStepBehavior microStep, SccBehavior scc) {
        this.taskType = taskType;
        this.microStep = microStep;
        this.scc = scc;
    }

    /** Task type string stored in {@link org.nebula.core.scheduler.TaskNode#taskType()}. */
    public String taskType() {
        return taskType;
    }

    /** Microstep behaviour for this component type. */
    public MicroStepBehavior microStepBehavior() {
        return microStep;
    }

    /** SCC contraction policy. */
    public SccBehavior sccBehavior() {
        return scc;
    }
}
