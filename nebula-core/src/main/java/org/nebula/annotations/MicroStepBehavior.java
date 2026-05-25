package org.nebula.annotations;

/** Controls how a task participates in within-tick microstep propagation. */
public enum MicroStepBehavior {
    /** Task may trigger new tasks in the same tick (e.g. redstone wire, fluid). */
    PROPAGATES,
    /** Task's output changes are deferred to the next tick (e.g. repeater delay). */
    DEFERRED,
    /** Task never triggers microstep propagation. */
    NONE
}
