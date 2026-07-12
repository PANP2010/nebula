package org.nebula.folia.bridge;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.math.Vec3;
import org.nebula.core.player.PlayerField;
import org.nebula.core.player.PlayerPhysicsState;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's {@link PlayerPhysicsState} CAS store and real Bukkit player state.
 *
 * <p>Read path: fetch player location/velocity/health via Bukkit API, commit to CAS.
 * <p>Write path: teleport/setVelocity via Bukkit API, update CAS.
 *
 * <p>Must be called on the region thread that owns the player's chunk.
 */
public final class NmsPlayerStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsPlayerStateBridge.class.getName());

    private final PlayerPhysicsState casStore;

    public NmsPlayerStateBridge(PlayerPhysicsState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    public static Player getPlayer(UUID uuid) {
        return org.bukkit.Bukkit.getPlayer(uuid);
    }

    @NebulaRW(
        readEntities       = {"{playerId}.position"},
        writeEntities     = {"{playerId}.position"},
        triggeredEvents    = {"PLAYER_MOVED"},
        microStep         = MicroStepBehavior.NONE,
        scc               = SccBehavior.SERIALIZED,
        maxRandomCalls    = 0,
        randomInstance    = "NONE",
        mayLoadChunks     = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities  = false,
        verifiedAt        = "1.21.4",
        verifiedBy        = {}
    )
    public long syncPositionFromNms(Player player) {
        Objects.requireNonNull(player, "player");
        Location loc = player.getLocation();
        Vec3 position = new Vec3(loc.getX(), loc.getY(), loc.getZ());
        PlayerField field = new PlayerField(player.getUniqueId(), "position");
        long expected = casStore.getVersion(field);
        casStore.casCommit(field, expected, position);
        return casStore.getVersion(field);
    }

    @NebulaRW(
        readEntities       = {"{playerId}.velocity"},
        writeEntities     = {"{playerId}.velocity"},
        triggeredEvents    = {"PLAYER_MOVED"},
        microStep         = MicroStepBehavior.NONE,
        scc               = SccBehavior.SERIALIZED,
        maxRandomCalls    = 0,
        randomInstance    = "NONE",
        mayLoadChunks     = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities  = false,
        verifiedAt        = "1.21.4",
        verifiedBy        = {}
    )
    public long syncVelocityFromNms(Player player) {
        Objects.requireNonNull(player, "player");
        Vector vel = player.getVelocity();
        Vec3 velocity = new Vec3(vel.getX(), vel.getY(), vel.getZ());
        PlayerField field = new PlayerField(player.getUniqueId(), "velocity");
        long expected = casStore.getVersion(field);
        casStore.casCommit(field, expected, velocity);
        return casStore.getVersion(field);
    }

    @NebulaRW(
        readEntities       = {"{playerId}.health"},
        writeEntities      = {"{playerId}.health"},
        triggeredEvents    = {"PLAYER_HEALTH_CHANGED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {}
    )
    public long syncHealthFromNms(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerField field = new PlayerField(player.getUniqueId(), "health");
        long expected = casStore.getVersion(field);
        casStore.casCommit(field, expected, player.getHealth());
        return casStore.getVersion(field);
    }

    @NebulaRW(
        readEntities       = {"{playerId}.hunger"},
        writeEntities      = {"{playerId}.hunger"},
        triggeredEvents    = {"PLAYER_HUNGER_CHANGED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {}
    )
    public long syncHungerFromNms(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerField field = new PlayerField(player.getUniqueId(), "hunger");
        long expected = casStore.getVersion(field);
        casStore.casCommit(field, expected, (double) player.getFoodLevel());
        return casStore.getVersion(field);
    }

    public void syncPhysicsFromNms(Player player) {
        syncPositionFromNms(player);
        syncVelocityFromNms(player);
    }

    public boolean syncPositionToNms(Player player, World world) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        PlayerField field = new PlayerField(player.getUniqueId(), "position");
        Vec3 position = casStore.getVec(field);
        if (position.equals(Vec3.ZERO)) {
            LOG.fine(() -> "No position in CAS for player " + player.getUniqueId());
            return false;
        }
        return player.teleport(new Location(world, position.x(), position.y(), position.z()));
    }

    public void syncVelocityToNms(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerField field = new PlayerField(player.getUniqueId(), "velocity");
        Vec3 velocity = casStore.getVec(field);
        player.setVelocity(new Vector(velocity.x(), velocity.y(), velocity.z()));
    }

    public void syncPhysicsToNms(Player player, World world) {
        syncPositionToNms(player, world);
        syncVelocityToNms(player);
    }

    public PlayerPhysicsState casStore() { return casStore; }
}
