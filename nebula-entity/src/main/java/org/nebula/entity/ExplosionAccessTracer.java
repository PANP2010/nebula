package org.nebula.entity;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

/** Optional hook that observes accesses performed through an {@link ExplosionContext}. */
public interface ExplosionAccessTracer {

    void onBlockRead(WorldPos pos);

    void onBlockWrite(WorldPos pos);

    void onEntityRead(EntityField field);

    void onEntityWrite(EntityField field);

    void onRandomCall(RandomInstance instance);
}
