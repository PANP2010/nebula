package org.nebula.guard;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.Objects;

public record AccessTarget(AccessTargetType type, String value) {
    public AccessTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    public static AccessTarget block(WorldPos pos) {
        return new AccessTarget(AccessTargetType.BLOCK, pos.toString());
    }

    public static AccessTarget blockEntityField(BlockEntityField field) {
        return new AccessTarget(AccessTargetType.BLOCK_ENTITY_FIELD, field.toString());
    }

    public static AccessTarget entityField(EntityField field) {
        return new AccessTarget(AccessTargetType.ENTITY_FIELD, field.toString());
    }

    public static AccessTarget globalKey(GlobalKey key) {
        return new AccessTarget(AccessTargetType.GLOBAL_KEY, key.value());
    }

    public static AccessTarget random(RandomInstance instance) {
        return new AccessTarget(AccessTargetType.RANDOM, instance.name());
    }
}
