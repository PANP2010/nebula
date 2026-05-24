package org.nebula.core.vap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * VAP debug tool: detects shared state access violations across plugin
 * boundaries (arch doc §13.5).
 *
 * <p>Tracks field reads/writes per thread and detects concurrent access to
 * the same field from different plugins without proper synchronization.
 * Violations are logged and can trigger an assertion in debug mode.
 */
public final class SharedStateAccessDetector {

    private final Map<String, AccessRecord> activeAccesses = new ConcurrentHashMap<>();
    private final List<Violation> violations = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;

    /**
     * Records a field access. If another plugin is currently accessing the
     * same field and at least one access is a write, a violation is recorded.
     *
     * @param pluginName the accessing plugin
     * @param fieldKey   unique identifier for the field (class#field)
     * @param isWrite    true if this is a write access
     */
    public void recordAccess(String pluginName, String fieldKey, boolean isWrite) {
        if (!enabled) return;

        AccessRecord existing = activeAccesses.get(fieldKey);
        if (existing != null && !existing.pluginName.equals(pluginName)) {
            if (isWrite || existing.isWrite) {
                violations.add(new Violation(
                    fieldKey,
                    existing.pluginName, existing.isWrite,
                    pluginName, isWrite,
                    Thread.currentThread().getName()
                ));
            }
        }

        activeAccesses.put(fieldKey, new AccessRecord(pluginName, isWrite, System.nanoTime()));
    }

    /**
     * Releases a field access record (called when the operation completes).
     */
    public void releaseAccess(String fieldKey) {
        activeAccesses.remove(fieldKey);
    }

    /**
     * Clears all active accesses (called at tick boundary).
     */
    public void tickReset() {
        activeAccesses.clear();
    }

    /**
     * Returns all recorded violations.
     */
    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    /**
     * Returns the number of violations detected.
     */
    public int violationCount() {
        return violations.size();
    }

    /**
     * Clears all recorded violations.
     */
    public void clearViolations() {
        violations.clear();
    }

    /**
     * Enables or disables the detector.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * A detected shared state access violation.
     */
    public record Violation(
        String fieldKey,
        String firstPlugin, boolean firstIsWrite,
        String secondPlugin, boolean secondIsWrite,
        String threadName
    ) {
        @Override
        public String toString() {
            String firstOp = firstIsWrite ? "WRITE" : "READ";
            String secondOp = secondIsWrite ? "WRITE" : "READ";
            return "Violation[" + fieldKey + "]: " + firstPlugin + "(" + firstOp + ") vs "
                + secondPlugin + "(" + secondOp + ") on " + threadName;
        }
    }

    private record AccessRecord(String pluginName, boolean isWrite, long timestampNs) {}
}
