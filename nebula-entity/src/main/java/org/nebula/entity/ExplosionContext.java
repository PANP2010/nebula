package org.nebula.entity;

import org.nebula.core.math.Vec3;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

/** Execution context that makes explosion block, entity, and random accesses traceable. */
public final class ExplosionContext {

    private final FluidState blockState;
    private final FluidStateSnapshot blockSnapshot;
    private final EntityPhysicsState entityState;
    private final EntityStateSnapshot entitySnapshot;
    private final DeterministicRandom random;
    private final RandomInstance randomInstance;
    private final ExplosionAccessTracer tracer;

    ExplosionContext(FluidState blockState, FluidStateSnapshot blockSnapshot,
                     EntityPhysicsState entityState, EntityStateSnapshot entitySnapshot,
                     DeterministicRandom random, RandomInstance randomInstance,
                     ExplosionAccessTracer tracer) {
        this.blockState = blockState;
        this.blockSnapshot = blockSnapshot;
        this.entityState = entityState;
        this.entitySnapshot = entitySnapshot;
        this.random = random;
        this.randomInstance = randomInstance;
        this.tracer = tracer;
    }

    public Object readBlock(WorldPos pos) {
        if (tracer != null) tracer.onBlockRead(pos);
        return blockSnapshot.read(blockState, pos);
    }

    public void writeBlock(WorldPos pos, Object value) {
        if (tracer != null) tracer.onBlockWrite(pos);
        blockSnapshot.write(pos, value);
    }

    public Vec3 readEntityVec(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onEntityRead(target);
        return entitySnapshot.readVec(entityState, target);
    }

    public double readEntityScalar(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onEntityRead(target);
        return entitySnapshot.readScalar(entityState, target);
    }

    public void writeEntityVec(long entityId, String field, Vec3 value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onEntityWrite(target);
        entitySnapshot.write(target, value);
    }

    public void writeEntityScalar(long entityId, String field, double value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onEntityWrite(target);
        entitySnapshot.write(target, value);
    }

    public int nextRandomInt(int bound) {
        if (random == null || randomInstance == null) {
            throw new IllegalStateException("Explosion action consumed RNG without declared RandomUsage");
        }
        if (tracer != null) tracer.onRandomCall(randomInstance);
        return random.nextInt(bound);
    }
}
