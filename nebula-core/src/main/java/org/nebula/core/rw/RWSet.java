package org.nebula.core.rw;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.PoiQuery;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record RWSet(
    Set<WorldPos> readBlocks,
    Set<WorldPos> writtenBlocks,
    Set<BlockEntityField> readBlockEntities,
    Set<BlockEntityField> writtenBlockEntities,
    Set<EntityField> readEntityFields,
    Set<EntityField> writtenEntityFields,
    Set<PoiQuery> readPoiQueries,
    Set<GlobalKey> readGlobalKeys,
    Set<GlobalKey> writtenGlobalKeys,
    Optional<RandomUsage> randomUsage,
    Set<EventType> writtenEvents
) {
    public RWSet {
        readBlocks = sortedCopy(readBlocks);
        writtenBlocks = sortedCopy(writtenBlocks);
        readBlockEntities = sortedCopy(readBlockEntities);
        writtenBlockEntities = sortedCopy(writtenBlockEntities);
        readEntityFields = sortedCopy(readEntityFields);
        writtenEntityFields = sortedCopy(writtenEntityFields);
        readPoiQueries = Set.copyOf(readPoiQueries);
        readGlobalKeys = sortedCopy(readGlobalKeys);
        writtenGlobalKeys = sortedCopy(writtenGlobalKeys);
        randomUsage = randomUsage == null ? Optional.empty() : randomUsage;
        writtenEvents = Set.copyOf(writtenEvents);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RWSet empty() {
        return builder().build();
    }

    public boolean declaresBlockRead(WorldPos pos) {
        return readBlocks.contains(pos);
    }

    public boolean declaresBlockWrite(WorldPos pos) {
        return writtenBlocks.contains(pos);
    }

    public boolean declaresEntityRead(EntityField field) {
        return containsEntityField(readEntityFields, field);
    }

    public boolean declaresEntityWrite(EntityField field) {
        return containsEntityField(writtenEntityFields, field);
    }

    public boolean declaresBlockEntityRead(BlockEntityField field) {
        return containsBlockEntityField(readBlockEntities, field);
    }

    public boolean declaresBlockEntityWrite(BlockEntityField field) {
        return containsBlockEntityField(writtenBlockEntities, field);
    }

    public boolean declaresGlobalRead(GlobalKey key) {
        return containsGlobalKey(readGlobalKeys, key);
    }

    public boolean declaresGlobalWrite(GlobalKey key) {
        return containsGlobalKey(writtenGlobalKeys, key);
    }

    public boolean hasReadWriteConflictWith(RWSet other) {
        // Fast-path: GLOBAL_RW × GLOBAL_RW always conflict on globals.
        if (writesGlobalWildcard() && other.readsGlobalWildcard()) {
            return true;
        }
        return intersectsWorldPos(writtenBlocks, other.readBlocks)
            || intersectsBlockEntity(writtenBlockEntities, other.readBlockEntities)
            || intersectsEntity(writtenEntityFields, other.readEntityFields)
            || intersectsGlobal(writtenGlobalKeys, other.readGlobalKeys);
    }

    public boolean hasWriteWriteConflictWith(RWSet other) {
        if (writesGlobalWildcard() && other.writesGlobalWildcard()) {
            return true;
        }
        return intersectsWorldPos(writtenBlocks, other.writtenBlocks)
            || intersectsBlockEntity(writtenBlockEntities, other.writtenBlockEntities)
            || intersectsEntity(writtenEntityFields, other.writtenEntityFields)
            || intersectsGlobal(writtenGlobalKeys, other.writtenGlobalKeys);
    }

    public boolean hasWriteReadConflictWith(RWSet other) {
        if (other.writesGlobalWildcard() && readsGlobalWildcard()) {
            return true;
        }
        return intersectsWorldPos(readBlocks, other.writtenBlocks)
            || intersectsBlockEntity(readBlockEntities, other.writtenBlockEntities)
            || intersectsEntity(readEntityFields, other.writtenEntityFields)
            || intersectsGlobal(readGlobalKeys, other.writtenGlobalKeys);
    }

    /**
     * True if this RWSet writes the wildcard global key ({@code *}) — i.e. it
     * is a GLOBAL_RW task that can mutate anything.
     */
    public boolean writesGlobalWildcard() {
        return writtenGlobalKeys.contains(GlobalKey.ALL);
    }

    /**
     * True if this RWSet reads the wildcard global key ({@code *}) — i.e. it
     * is a GLOBAL_RW task that observes anything.
     */
    public boolean readsGlobalWildcard() {
        return readGlobalKeys.contains(GlobalKey.ALL);
    }

    /**
     * True if the only writes are to entity fields (writtenEntityFields) — no
     * blocks, no block-entities, no globals, no events. Two such RWSets can
     * never conflict with each other unless they target the same entityId,
     * which is impossible across distinct entities. Used by BucketDagBuilder
     * to skip O(K²) conflict detection for the common "self-only" entity tick
     * pattern.
     */
    public boolean isSelfOnlyEntityWrite() {
        return writtenBlocks.isEmpty()
            && writtenBlockEntities.isEmpty()
            && writtenGlobalKeys.isEmpty()
            && writtenEvents.isEmpty();
    }

    /**
     * True if this RWSet has any read or write entry in the global key space.
     * Used by BucketDagBuilder to skip pairing positional-only tasks with
     * global tasks (they can never conflict on globals).
     */
    public boolean touchesGlobals() {
        return !readGlobalKeys.isEmpty() || !writtenGlobalKeys.isEmpty();
    }

    /**
     * Returns a new RWSet that is the union of this set and {@code other}.
     * Used by SCC contraction to build compound-task RW-sets.
     */
    public RWSet merge(RWSet other) {
        Builder b = new Builder();
        readBlocks.forEach(b::readBlock);
        other.readBlocks.forEach(b::readBlock);
        writtenBlocks.forEach(b::writeBlock);
        other.writtenBlocks.forEach(b::writeBlock);
        readBlockEntities.forEach(b::readBlockEntity);
        other.readBlockEntities.forEach(b::readBlockEntity);
        writtenBlockEntities.forEach(b::writeBlockEntity);
        other.writtenBlockEntities.forEach(b::writeBlockEntity);
        readEntityFields.forEach(b::readEntity);
        other.readEntityFields.forEach(b::readEntity);
        writtenEntityFields.forEach(b::writeEntity);
        other.writtenEntityFields.forEach(b::writeEntity);
        readPoiQueries.forEach(b::readPoi);
        other.readPoiQueries.forEach(b::readPoi);
        readGlobalKeys.forEach(b::readGlobal);
        other.readGlobalKeys.forEach(b::readGlobal);
        writtenGlobalKeys.forEach(b::writeGlobal);
        other.writtenGlobalKeys.forEach(b::writeGlobal);
        writtenEvents.forEach(b::writeEvent);
        other.writtenEvents.forEach(b::writeEvent);
        if (randomUsage.isPresent() || other.randomUsage.isPresent()) {
            int total = randomUsage.map(r -> r.maxCallsEstimate()).orElse(0)
                      + other.randomUsage.map(r -> r.maxCallsEstimate()).orElse(0);
            var instance = randomUsage.or(() -> other.randomUsage).map(r -> r.instance()).orElse(null);
            if (instance != null) {
                b.randomUsage(new org.nebula.core.state.RandomUsage(instance, total));
            }
        }
        return b.build();
    }

    private static <T extends Comparable<? super T>> Set<T> sortedCopy(Collection<T> values) {
        return Set.copyOf(new TreeSet<>(values));
    }

    private static boolean containsEntityField(Set<EntityField> declared, EntityField actual) {
        for (EntityField candidate : declared) {
            if (candidate.conflictsWith(actual)) return true;
        }
        return false;
    }

    private static boolean containsBlockEntityField(Set<BlockEntityField> declared, BlockEntityField actual) {
        for (BlockEntityField candidate : declared) {
            if (candidate.conflictsWith(actual)) return true;
        }
        return false;
    }

    private static boolean containsGlobalKey(Set<GlobalKey> declared, GlobalKey actual) {
        for (GlobalKey candidate : declared) {
            if (candidate.conflictsWith(actual)) return true;
        }
        return false;
    }

    private static boolean intersectsWorldPos(Set<WorldPos> left, Set<WorldPos> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        // Iterate the smaller set for fewer hash lookups.
        if (left.size() > right.size()) { Set<WorldPos> swap = left; left = right; right = swap; }
        for (WorldPos pos : left) {
            if (right.contains(pos)) return true;
        }
        return false;
    }

    private static boolean intersectsEntity(Set<EntityField> left, Set<EntityField> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        for (EntityField l : left) {
            for (EntityField r : right) {
                if (l.conflictsWith(r)) return true;
            }
        }
        return false;
    }

    private static boolean intersectsBlockEntity(Set<BlockEntityField> left, Set<BlockEntityField> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        for (BlockEntityField l : left) {
            for (BlockEntityField r : right) {
                if (l.conflictsWith(r)) return true;
            }
        }
        return false;
    }

    private static boolean intersectsGlobal(Set<GlobalKey> left, Set<GlobalKey> right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        for (GlobalKey l : left) {
            for (GlobalKey r : right) {
                if (l.conflictsWith(r)) return true;
            }
        }
        return false;
    }

    public static final class Builder {
        private final Set<WorldPos> readBlocks = new TreeSet<>();
        private final Set<WorldPos> writtenBlocks = new TreeSet<>();
        private final Set<BlockEntityField> readBlockEntities = new TreeSet<>();
        private final Set<BlockEntityField> writtenBlockEntities = new TreeSet<>();
        private final Set<EntityField> readEntityFields = new TreeSet<>();
        private final Set<EntityField> writtenEntityFields = new TreeSet<>();
        private final Set<PoiQuery> readPoiQueries = new java.util.LinkedHashSet<>();
        private final Set<GlobalKey> readGlobalKeys = new TreeSet<>();
        private final Set<GlobalKey> writtenGlobalKeys = new TreeSet<>();
        private final Set<EventType> writtenEvents = new java.util.LinkedHashSet<>();
        private RandomUsage randomUsage;

        public Builder readBlock(WorldPos pos) {
            readBlocks.add(pos);
            return this;
        }

        public Builder writeBlock(WorldPos pos) {
            writtenBlocks.add(pos);
            return this;
        }

        public Builder readBlockEntity(BlockEntityField field) {
            readBlockEntities.add(field);
            return this;
        }

        public Builder writeBlockEntity(BlockEntityField field) {
            writtenBlockEntities.add(field);
            return this;
        }

        public Builder readEntity(EntityField field) {
            readEntityFields.add(field);
            return this;
        }

        public Builder writeEntity(EntityField field) {
            writtenEntityFields.add(field);
            return this;
        }

        public Builder readPoi(PoiQuery query) {
            readPoiQueries.add(query);
            return this;
        }

        public Builder readGlobal(GlobalKey key) {
            readGlobalKeys.add(key);
            return this;
        }

        public Builder writeGlobal(GlobalKey key) {
            writtenGlobalKeys.add(key);
            return this;
        }

        public Builder randomUsage(RandomUsage usage) {
            randomUsage = usage;
            return this;
        }

        public Builder writeEvent(EventType eventType) {
            writtenEvents.add(eventType);
            return this;
        }

        public RWSet build() {
            return new RWSet(
                readBlocks,
                writtenBlocks,
                readBlockEntities,
                writtenBlockEntities,
                readEntityFields,
                writtenEntityFields,
                readPoiQueries,
                readGlobalKeys,
                writtenGlobalKeys,
                Optional.ofNullable(randomUsage),
                writtenEvents
            );
        }
    }
}
