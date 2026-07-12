package org.nebula.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * Player-scoped field identifier, keyed by {@link UUID} (the player's unique ID).
 *
 * <p>Mirrors {@link org.nebula.core.state.EntityField} but for player state:
 * position, velocity, health, hunger, held slot, etc.
 *
 * <p>Field paths:
 * <ul>
 *   <li>{@code position} — Vec3</li>
 *   <li>{@code velocity} — Vec3</li>
 *   <li>{@code health} — double (0–20)</li>
 *   <li>{@code hunger} — double (0–20)</li>
 *   <li>{@code held_slot} — int (0–8)</li>
 *   <li>{@code slot_N_count} — int (stack count in slot N)</li>
 *   <li>{@code is_breaking_block} — boolean</li>
 *   <li>{@code break_progress} — double</li>
 *   <li>{@code break_speed} — double</li>
 * </ul>
 */
public record PlayerField(UUID playerId, String fieldPath) implements Comparable<PlayerField> {

    /** Canonical constructor (UUID + path). */
    public PlayerField(UUID playerId, String fieldPath) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fieldPath = Objects.requireNonNull(fieldPath, "fieldPath");
    }

    /** Alternate constructor: long entityId + path. Used for targeting other entities in combat/interact. */
    public static PlayerField ofEntity(long entityId, String fieldPath) {
        return new PlayerField(new UUID(0L, entityId), Objects.requireNonNull(fieldPath, "fieldPath"));
    }

    /** Returns true if this field uses a long entity ID (UUID most-significant-bits = 0). */
    public boolean isEntityId() {
        return playerId.getMostSignificantBits() == 0L;
    }

    /** Returns the long entity ID, or throws if this is a real UUID-based field. */
    public long asEntityId() {
        if (playerId.getMostSignificantBits() != 0L) {
            throw new IllegalStateException("Not an entity-ID field: " + playerId);
        }
        return playerId.getLeastSignificantBits();
    }

    public boolean conflictsWith(PlayerField other) {
        return playerId.equals(other.playerId) && fieldPath.equals(other.fieldPath);
    }

    @Override
    public int compareTo(PlayerField other) {
        int byPlayer = playerId.compareTo(other.playerId);
        if (byPlayer != 0) return byPlayer;
        return fieldPath.compareTo(other.fieldPath);
    }

    public static PlayerField parse(String value) {
        String[] parts = value.split(":", 2);
        UUID id = UUID.fromString(parts[0]);
        String path = parts.length > 1 ? parts[1] : "";
        return new PlayerField(id, path);
    }
}