package org.nebula.core.vap;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Runtime proxy for {@link ManagedState}-annotated fields (arch doc §13.3).
 *
 * <p>Scans a plugin instance for @ManagedState fields and wraps each one
 * with the appropriate concurrency control (MVCC, ATOMIC, or LOCK).
 * Field access is then routed through the proxy instead of direct field
 * access.
 *
 * <p>The JVM agent rewrites field get/put instructions in Level 1 plugins
 * to call {@link #read(String)} and {@link #write(String, Object)} on the
 * proxy instance held by the plugin's VAP context.
 */
public final class ManagedStateProxy {

    private final Map<String, FieldProxy<?>> proxies = new ConcurrentHashMap<>();
    private final String pluginName;

    public ManagedStateProxy(String pluginName) {
        this.pluginName = Objects.requireNonNull(pluginName);
    }

    /**
     * Scans the target object for @ManagedState fields and registers proxies.
     *
     * @param target the plugin instance to scan
     * @throws IllegalStateException if a field cannot be proxied
     */
    @SuppressWarnings("unchecked")
    public void register(Object target) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                ManagedState annotation = field.getAnnotation(ManagedState.class);
                if (annotation == null) continue;

                field.setAccessible(true);
                try {
                    Object initialValue = field.get(target);
                    if (initialValue == null) {
                        throw new IllegalStateException(
                            "@ManagedState field '" + field.getName() + "' in " + clazz.getName()
                                + " must be initialized (non-null)");
                    }

                    FieldProxy<?> proxy = createProxy(field.getName(), annotation, initialValue);
                    proxies.put(field.getName(), proxy);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot access @ManagedState field: " + field.getName(), e);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FieldProxy<?> createProxy(String fieldName, ManagedState annotation, Object initialValue) {
        return switch (annotation.strategy()) {
            case MVCC -> new MvccFieldProxy(fieldName, initialValue, resolveMergeFunction(annotation));
            case ATOMIC -> new AtomicFieldProxy(fieldName, initialValue);
            case LOCK -> new LockFieldProxy(fieldName, initialValue);
        };
    }

    @SuppressWarnings("unchecked")
    private BinaryOperator<Object> resolveMergeFunction(ManagedState annotation) {
        if (annotation.mergeFunction().isEmpty()) {
            return (a, b) -> b;
        }
        return (a, b) -> b;
    }

    /**
     * Reads a managed field through its concurrency proxy.
     *
     * @param fieldName the field name
     * @return the field value
     */
    public Object read(String fieldName) {
        FieldProxy<?> proxy = proxies.get(fieldName);
        if (proxy == null) {
            throw new IllegalArgumentException("No managed field: " + fieldName);
        }
        return proxy.read();
    }

    /**
     * Writes a managed field through its concurrency proxy.
     *
     * @param fieldName the field name
     * @param value     the new value
     */
    @SuppressWarnings("unchecked")
    public void write(String fieldName, Object value) {
        FieldProxy<?> proxy = proxies.get(fieldName);
        if (proxy == null) {
            throw new IllegalArgumentException("No managed field: " + fieldName);
        }
        ((FieldProxy<Object>) proxy).write(value);
    }

    /**
     * Updates a managed field atomically using a transform function.
     */
    @SuppressWarnings("unchecked")
    public Object update(String fieldName, UnaryOperator<Object> transform) {
        FieldProxy<?> proxy = proxies.get(fieldName);
        if (proxy == null) {
            throw new IllegalArgumentException("No managed field: " + fieldName);
        }
        return ((FieldProxy<Object>) proxy).update(transform);
    }

    /**
     * Takes a snapshot of all MVCC fields at tick start.
     */
    public void snapshotAll() {
        for (FieldProxy<?> proxy : proxies.values()) {
            if (proxy instanceof MvccFieldProxy<?> mvcc) {
                mvcc.snapshot();
            }
        }
    }

    /**
     * Returns the number of registered managed fields.
     */
    public int fieldCount() {
        return proxies.size();
    }

    /**
     * Returns the concurrency strategy for a field.
     */
    public ConcurrencyStrategy strategyFor(String fieldName) {
        FieldProxy<?> proxy = proxies.get(fieldName);
        if (proxy == null) return null;
        return proxy.strategy();
    }

    public String pluginName() {
        return pluginName;
    }

    sealed interface FieldProxy<T> permits MvccFieldProxy, AtomicFieldProxy, LockFieldProxy {
        T read();
        void write(T value);
        T update(UnaryOperator<T> transform);
        ConcurrencyStrategy strategy();
    }

    static final class MvccFieldProxy<T> implements FieldProxy<T> {
        private final String fieldName;
        private final MvccVersionStore<T> store;

        MvccFieldProxy(String fieldName, T initial, BinaryOperator<T> mergeFunction) {
            this.fieldName = fieldName;
            this.store = new MvccVersionStore<>(initial, mergeFunction);
        }

        void snapshot() {
            store.snapshot();
        }

        @Override
        public T read() {
            return store.readLatest();
        }

        @Override
        public void write(T value) {
            store.set(value);
        }

        @Override
        public T update(UnaryOperator<T> transform) {
            return store.update(transform);
        }

        @Override
        public ConcurrencyStrategy strategy() {
            return ConcurrencyStrategy.MVCC;
        }

        MvccVersionStore<T> store() {
            return store;
        }
    }

    static final class AtomicFieldProxy<T> implements FieldProxy<T> {
        private final String fieldName;
        private final AtomicReference<T> ref;

        AtomicFieldProxy(String fieldName, T initial) {
            this.fieldName = fieldName;
            this.ref = new AtomicReference<>(initial);
        }

        @Override
        public T read() {
            return ref.get();
        }

        @Override
        public void write(T value) {
            ref.set(value);
        }

        @Override
        public T update(UnaryOperator<T> transform) {
            return ref.updateAndGet(transform);
        }

        @Override
        public ConcurrencyStrategy strategy() {
            return ConcurrencyStrategy.ATOMIC;
        }
    }

    static final class LockFieldProxy<T> implements FieldProxy<T> {
        private final String fieldName;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private T value;

        LockFieldProxy(String fieldName, T initial) {
            this.fieldName = fieldName;
            this.value = initial;
        }

        @Override
        public T read() {
            lock.readLock().lock();
            try {
                return value;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void write(T newValue) {
            lock.writeLock().lock();
            try {
                this.value = newValue;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public T update(UnaryOperator<T> transform) {
            lock.writeLock().lock();
            try {
                this.value = transform.apply(this.value);
                return this.value;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public ConcurrencyStrategy strategy() {
            return ConcurrencyStrategy.LOCK;
        }
    }
}
