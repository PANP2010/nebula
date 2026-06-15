package org.nebula.core.scheduler;

import java.util.List;

/**
 * A {@link TaskRunner} that buffers each layer's writes and commits them as a
 * unit (arch doc §4.4 write-buffer / CAS commit model).
 *
 * <p>Subsystem runners (redstone, entity physics) execute a layer's tasks into
 * per-task snapshots, then commit them together via {@link #commitLayer()} so a
 * layer's writes become visible atomically and stale reads can be retried. This
 * interface lets a {@link CompositeTaskRunner} drive several such runners
 * through one shared layer lifecycle.
 */
public interface LayerCommitting extends TaskRunner {

    /**
     * Commits all writes buffered while executing the current layer.
     *
     * @return task IDs whose commit failed (e.g. stale read); empty on success
     */
    List<String> commitLayer();

    /** Clears the current layer's buffered state, readying the next layer. */
    void resetLayer();
}
