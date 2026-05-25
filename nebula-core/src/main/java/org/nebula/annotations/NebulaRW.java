package org.nebula.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the read/write set of a Minecraft tick method so the Nebula
 * DAG scheduler can determine data dependencies and parallelism.
 *
 * <p>Syntax for position/field placeholders follows the pattern
 * {@code {param.field}} where {@code param} refers to a method parameter
 * name and {@code field} is an optional sub-path.  E.g.
 * {@code "{pos}"}, {@code "{pos.backward}"}, {@code "{entityId}"}.
 *
 * <p>Example:
 * <pre>{@code
 * @NebulaRW(
 *   readBlocks  = {"{pos.backward}"},
 *   writeBlocks = {"{pos.forward}"},
 *   readInternalState  = {"delay_counter", "current_output"},
 *   writeInternalState = {"delay_counter", "current_output"},
 *   triggeredEvents    = {"BLOCK_UPDATE"},
 *   microStep = MicroStepBehavior.DEFERRED,
 *   scc       = SccBehavior.CONTRACTIBLE,
 *   maxRandomCalls = 0,
 *   mayLoadChunks     = false,
 *   maySpawnEntities  = false,
 *   maxCallDepth      = 3
 * )
 * public void tickRedstoneRepeater(BlockPos pos) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NebulaRW {

    // ── Block positions ──────────────────────────────────────────────────────

    /** Block coordinates this method reads (placeholder syntax allowed). */
    String[] readBlocks() default {};

    /** Block coordinates this method writes (placeholder syntax allowed). */
    String[] writeBlocks() default {};

    // ── Block-entity fields ──────────────────────────────────────────────────

    /** Block-entity fields this method reads, e.g. {@code "inventory.slots[0]"}. */
    String[] readBlockEntities() default {};

    /** Block-entity fields this method writes. */
    String[] writeBlockEntities() default {};

    // ── Entity fields ────────────────────────────────────────────────────────

    /**
     * Entity fields this method reads.
     * Format: {@code "<entityParam>.<fieldPath>"}, e.g. {@code "entityId.position"}.
     */
    String[] readEntities() default {};

    /** Entity fields this method writes. */
    String[] writeEntities() default {};

    // ── Global state ─────────────────────────────────────────────────────────

    /**
     * Global state keys this method reads.
     * Use {@code "*"} for all global state.
     * See {@code GlobalKey} for known constants.
     */
    String[] readGlobals() default {};

    /** Global state keys this method writes. */
    String[] writeGlobals() default {};

    // ── Internal / opaque state ───────────────────────────────────────────────

    /** Names of internal state fields read (e.g. delay counters). */
    String[] readInternalState() default {};

    /** Names of internal state fields written. */
    String[] writeInternalState() default {};

    // ── Events ───────────────────────────────────────────────────────────────

    /**
     * Event types this method may fire (from {@code EventType} enum names),
     * e.g. {@code "BLOCK_UPDATE"}, {@code "ENTITY_MOVED"}.
     */
    String[] triggeredEvents() default {};

    // ── Random ───────────────────────────────────────────────────────────────

    /**
     * Upper-bound on Random calls made by this method per invocation.
     * 0 means no Random usage.
     */
    int maxRandomCalls() default 0;

    /**
     * Which Random instance is consumed.
     * Matches {@code RandomInstance} enum names: {@code "ENTITY_RANDOM"},
     * {@code "WORLD_RANDOM"}, {@code "NONE"}, etc.
     */
    String randomInstance() default "NONE";

    // ── Closure properties ───────────────────────────────────────────────────

    /** Maximum call depth explored when building the closure annotation. */
    int maxCallDepth() default 5;

    /** Whether this method may trigger synchronous chunk loads. */
    boolean mayLoadChunks() default false;

    /** Whether this method may trigger block-update events. */
    boolean mayTriggerBlockUpdates() default false;

    /** Whether this method may spawn entities. */
    boolean maySpawnEntities() default false;

    // ── Scheduling hints ──────────────────────────────────────────────────────

    /** Microstep propagation behaviour for this task. */
    MicroStepBehavior microStep() default MicroStepBehavior.NONE;

    /** SCC contraction policy for this task. */
    SccBehavior scc() default SccBehavior.AUTO;

    // ── Metadata ─────────────────────────────────────────────────────────────

    /** Minecraft version this annotation was verified against, e.g. {@code "1.21.4"}. */
    String verifiedAt() default "";

    /** Test method names that validate this annotation. */
    String[] verifiedBy() default {};
}
