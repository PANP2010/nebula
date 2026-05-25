package org.nebula.annotations;

/** How a task should behave when it is part of a strongly-connected component. */
public enum SccBehavior {
    /** SCC can be contracted into a single compound task (physical loop, e.g. repeater ring). */
    CONTRACTIBLE,
    /** Task must be serialised within the SCC without contraction. */
    SERIALIZED,
    /** Default: let the SCC contractor decide based on size threshold. */
    AUTO
}
