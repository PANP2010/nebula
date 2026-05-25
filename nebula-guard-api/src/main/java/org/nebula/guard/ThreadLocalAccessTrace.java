package org.nebula.guard;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ThreadLocalAccessTrace {
    private static final ThreadLocal<ThreadLocalAccessTrace> CURRENT =
        ThreadLocal.withInitial(ThreadLocalAccessTrace::new);

    private final Set<WorldPos> readBlocks = new HashSet<>();
    private final Set<WorldPos> writtenBlocks = new HashSet<>();
    private final Set<BlockEntityField> readBlockEntities = new HashSet<>();
    private final Set<BlockEntityField> writtenBlockEntities = new HashSet<>();
    private final Set<EntityField> readEntityFields = new HashSet<>();
    private final Set<EntityField> writtenEntityFields = new HashSet<>();
    private final Set<GlobalKey> readGlobalKeys = new HashSet<>();
    private final Set<GlobalKey> writtenGlobalKeys = new HashSet<>();
    private final Map<RandomInstance, Integer> randomCalls = new EnumMap<>(RandomInstance.class);

    private ThreadLocalAccessTrace() {
    }

    public static void reset() {
        CURRENT.get().clear();
    }

    public static ActualAccessTrace snapshot() {
        ThreadLocalAccessTrace trace = CURRENT.get();
        return new ActualAccessTrace(
            trace.readBlocks,
            trace.writtenBlocks,
            trace.readBlockEntities,
            trace.writtenBlockEntities,
            trace.readEntityFields,
            trace.writtenEntityFields,
            trace.readGlobalKeys,
            trace.writtenGlobalKeys,
            trace.randomCalls
        );
    }

    public static void traceBlockRead(WorldPos pos) {
        CURRENT.get().readBlocks.add(pos);
    }

    public static void traceBlockWrite(WorldPos pos) {
        CURRENT.get().writtenBlocks.add(pos);
    }

    public static void traceBlockEntityRead(BlockEntityField field) {
        CURRENT.get().readBlockEntities.add(field);
    }

    public static void traceBlockEntityWrite(BlockEntityField field) {
        CURRENT.get().writtenBlockEntities.add(field);
    }

    public static void traceEntityRead(EntityField field) {
        CURRENT.get().readEntityFields.add(field);
    }

    public static void traceEntityWrite(EntityField field) {
        CURRENT.get().writtenEntityFields.add(field);
    }

    public static void traceGlobalRead(GlobalKey key) {
        CURRENT.get().readGlobalKeys.add(key);
    }

    public static void traceGlobalWrite(GlobalKey key) {
        CURRENT.get().writtenGlobalKeys.add(key);
    }

    public static void traceRandomCall(RandomInstance instance) {
        CURRENT.get().randomCalls.merge(instance, 1, Integer::sum);
    }

    private void clear() {
        readBlocks.clear();
        writtenBlocks.clear();
        readBlockEntities.clear();
        writtenBlockEntities.clear();
        readEntityFields.clear();
        writtenEntityFields.clear();
        readGlobalKeys.clear();
        writtenGlobalKeys.clear();
        randomCalls.clear();
    }
}
