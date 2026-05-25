package org.nebula.guard;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.Set;

public record ActualAccessTrace(
    Set<WorldPos> readBlocks,
    Set<WorldPos> writtenBlocks,
    Set<BlockEntityField> readBlockEntities,
    Set<BlockEntityField> writtenBlockEntities,
    Set<EntityField> readEntityFields,
    Set<EntityField> writtenEntityFields,
    Set<GlobalKey> readGlobalKeys,
    Set<GlobalKey> writtenGlobalKeys,
    Map<RandomInstance, Integer> randomCalls
) {
    public ActualAccessTrace {
        readBlocks = Set.copyOf(readBlocks);
        writtenBlocks = Set.copyOf(writtenBlocks);
        readBlockEntities = Set.copyOf(readBlockEntities);
        writtenBlockEntities = Set.copyOf(writtenBlockEntities);
        readEntityFields = Set.copyOf(readEntityFields);
        writtenEntityFields = Set.copyOf(writtenEntityFields);
        readGlobalKeys = Set.copyOf(readGlobalKeys);
        writtenGlobalKeys = Set.copyOf(writtenGlobalKeys);
        randomCalls = Map.copyOf(randomCalls);
    }
}
