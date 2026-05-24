package org.nebula.core.vap;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a plugin field requires automatic concurrency control (arch doc §13.3).
 *
 * <p>VAP Level 1 plugins annotate their shared data structures with this
 * annotation. The Nebula agent generates proxies that provide the declared
 * concurrency strategy without manual synchronization.
 *
 * <p>Example:
 * <pre>{@code
 * @ManagedState(strategy = ConcurrencyStrategy.MVCC)
 * private Map<UUID, PlayerBalance> balances;
 *
 * @ManagedState(strategy = ConcurrencyStrategy.ATOMIC)
 * private AtomicInteger transactionCount;
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ManagedState {

    /**
     * Concurrency strategy for this field.
     */
    ConcurrencyStrategy strategy();

    /**
     * Name of a static merge function for MVCC conflict resolution.
     * Required when strategy = MVCC. The method must accept two values of
     * the field's type and return the merged result.
     *
     * <p>If empty with MVCC, the default last-writer-wins strategy is used
     * (with a warning logged at startup).
     */
    String mergeFunction() default "";
}
