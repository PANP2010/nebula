package org.nebula.player;

import org.nebula.core.player.PlayerPhysicsState;
import org.nebula.core.player.PlayerField;
import java.util.UUID;

/**
 * Minecraft survival mechanics: health, hunger, saturation, exhaustion, experience.
 * All values are per-tick deltas that accumulate into PlayerPhysicsState.
 *
 * Minecraft mechanics modeled:
 * - Health: 0-20 (half-hearts). Death at 0.
 * - Hunger: 0-20 (drumsticks). At 0, health drains.
 * - Saturation: 0-20. Eaten food restores both hunger AND saturation.
 * - Exhaustion: 0-40. At 40, hunger drains by 1 and exhaustion resets.
 * - XP: levels and progress toward next level.
 */
public final class SurvivalAttributes {

    public static final double MAX_HEALTH = 20.0;
    public static final double MAX_HUNGER = 20.0;
    public static final double EXHAUSTION_PER_HUNGER_DRAIN = 4.0;
    public static final double HUNGER_PER_HEALTH_DRAIN_TICK = 80.0; // 4 seconds of hunger=0 before health drains

    private final PlayerPhysicsState state;

    public SurvivalAttributes(PlayerPhysicsState state) {
        this.state = state;
    }

    /** Returns current health (0-20). */
    public double getHealth(UUID playerId) {
        return state.getScalar(new PlayerField(playerId, "health"));
    }

    /** Sets health. */
    public void setHealth(UUID playerId, double health) {
        double clamped = Math.max(0, Math.min(MAX_HEALTH, health));
        state.put(new PlayerField(playerId, "health"), clamped);
    }

    /** Returns hunger (0-20). */
    public double getHunger(UUID playerId) {
        return state.getScalar(new PlayerField(playerId, "hunger"));
    }

    /** Sets hunger. */
    public void setHunger(UUID playerId, double hunger) {
        double clamped = Math.max(0, Math.min(MAX_HUNGER, hunger));
        state.put(new PlayerField(playerId, "hunger"), clamped);
    }

    /** Returns saturation (0-20). */
    public double getSaturation(UUID playerId) {
        return state.getScalar(new PlayerField(playerId, "saturation"));
    }

    /** Sets saturation (capped by hunger). */
    public void setSaturation(UUID playerId, double saturation) {
        double h = getHunger(playerId);
        double clamped = Math.max(0, Math.min(h, saturation));
        state.put(new PlayerField(playerId, "saturation"), clamped);
    }

    /** Returns exhaustion (0-40). */
    public double getExhaustion(UUID playerId) {
        return state.getScalar(new PlayerField(playerId, "exhaustion"));
    }

    /** Adds exhaustion. Drains hunger if threshold reached. */
    public void addExhaustion(UUID playerId, double amount) {
        double current = getExhaustion(playerId) + amount;
        if (current >= EXHAUSTION_PER_HUNGER_DRAIN) {
            current -= EXHAUSTION_PER_HUNGER_DRAIN;
            double h = getHunger(playerId) - 1;
            if (h < 0) h = 0;
            setHunger(playerId, h);
        }
        state.put(new PlayerField(playerId, "exhaustion"), Math.max(0, current));
    }

    /** Tick: called every game tick. Drains health if hunger is 0. */
    public void tick(UUID playerId) {
        double hunger = getHunger(playerId);
        if (hunger <= 0) {
            double health = getHealth(playerId) - 0.5; // 1 health per 4 seconds
            if (health < 0) health = 0;
            setHealth(playerId, health);
        }
    }

    /** Eat food: restores hunger and saturation. */
    public void eat(UUID playerId, double hungerRestore, double saturationRestore) {
        double hunger = getHunger(playerId);
        double saturation = getSaturation(playerId);
        hunger = Math.min(MAX_HUNGER, hunger + hungerRestore);
        saturation = Math.min(hunger, saturation + saturationRestore);
        setHunger(playerId, hunger);
        setSaturation(playerId, saturation);
        addExhaustion(playerId, 6.0); // eating costs exhaustion
    }

    /** Returns the current XP level. */
    public int getLevel(UUID playerId) {
        return (int) state.getScalar(new PlayerField(playerId, "xp_level"));
    }

    /** Adds XP progress. Handles level-up. */
    public void addXp(UUID playerId, int amount) {
        double current = state.getScalar(new PlayerField(playerId, "xp_progress"));
        double needed = xpToNextLevel(getLevel(playerId));
        current += amount;
        while (current >= needed) {
            current -= needed;
            levelUp(playerId);
            needed = xpToNextLevel(getLevel(playerId));
        }
        state.put(new PlayerField(playerId, "xp_progress"), current);
    }

    private void levelUp(UUID playerId) {
        int lvl = getLevel(playerId) + 1;
        state.put(new PlayerField(playerId, "xp_level"), (double) lvl);
    }

    /** XP needed to reach next level. Approximation of MC formula. */
    public static double xpToNextLevel(int level) {
        if (level < 16) return 17;
        if (level < 30) return 3 * level + 7;
        return 7 * level - 59;
    }
}
