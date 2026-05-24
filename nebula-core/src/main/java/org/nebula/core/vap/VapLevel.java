package org.nebula.core.vap;

/**
 * VAP compatibility level for plugins (arch doc §13.1).
 */
public enum VapLevel {
    /**
     * Level 0: Plugin calls are intercepted and serialized into a dedicated
     * DAG phase after all kernel tasks complete. Zero plugin modification needed.
     */
    LEVEL_0,

    /**
     * Level 1: Plugin uses @ManagedState for automatic concurrency control.
     * Partial parallelism within the plugin phase.
     */
    LEVEL_1,

    /**
     * Level 2: Plugin submits explicit RW-set tasks via the Nebula API.
     * Full DAG participation alongside kernel tasks.
     */
    LEVEL_2,

    /**
     * Sandboxed: Plugin runs in an isolated single-thread region with
     * message-passing API calls (~50-200μs overhead per call).
     */
    SANDBOXED
}
