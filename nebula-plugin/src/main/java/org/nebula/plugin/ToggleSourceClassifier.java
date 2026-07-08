package org.nebula.plugin;

import java.util.Set;

/**
 * Decides, from a Bukkit {@code Material} name, whether a block is a
 * <em>manual toggle source</em> — a lever or button the live-load driver
 * ({@link org.nebula.replay.LiveLoadToggleDriver}) may deterministically flip.
 *
 * <h3>Why this exists (the blocker it clears)</h3>
 * {@link WorldRedstoneScanner} maps several distinct materials onto a single
 * DAG component type: {@code LEVER}, {@code STONE_BUTTON}, {@code OAK_BUTTON}
 * (and friends) all register as {@link org.nebula.redstone.RedstoneComponentType#REDSTONE_TORCH}.
 * That collapse is correct for the DAG — a lever's power-propagation shape is a
 * torch's — but it is lossy: once a position lands in {@code componentMap} as
 * {@code REDSTONE_TORCH}, nothing can tell a lever from an actual redstone
 * torch. The live-load driver needs the lever/button positions recoverable so it
 * can drive a deterministic toggle stream, and the scanner is the one place the
 * original material name is still known.
 *
 * <p>This classifier is pure (a name → boolean function over a fixed name set),
 * so it is fully unit-testable with no Folia/NMS types. It deliberately does
 * <strong>not</strong> depend on {@code org.bukkit.Material} for the same reason
 * {@link WorldRedstoneScanner}'s {@code TYPE_MAP} uses string keys: to avoid
 * Material enum static-init issues under the test classloader.
 *
 * <h3>What counts as a toggle source</h3>
 * A block whose powered state a player flips <em>by hand</em> and that then holds
 * (or pulses) that state without further redstone input — i.e. levers and the
 * button family. Pressure plates, observers, and tripwire hooks are also inputs
 * to redstone, but they are driven by world events (entities, block updates), not
 * by a manual on/off flip, so they are NOT toggle sources for the driver and are
 * excluded here. Keeping the set narrow keeps the driven toggle stream faithful
 * to what a deterministic replay can actually reproduce.
 */
public final class ToggleSourceClassifier {

    /**
     * Material names (as {@code Material.name()} produces) that are manual toggle
     * sources. Buttons are enumerated by wood/stone variant because Bukkit has no
     * single {@code BUTTON} material — each is its own {@code Material} constant.
     */
    private static final Set<String> TOGGLE_SOURCE_NAMES = Set.of(
        "LEVER",
        // Stone / metal buttons
        "STONE_BUTTON",
        "POLISHED_BLACKSTONE_BUTTON",
        // Wooden buttons (all vanilla wood types)
        "OAK_BUTTON",
        "SPRUCE_BUTTON",
        "BIRCH_BUTTON",
        "JUNGLE_BUTTON",
        "ACACIA_BUTTON",
        "DARK_OAK_BUTTON",
        "MANGROVE_BUTTON",
        "CHERRY_BUTTON",
        "PALE_OAK_BUTTON",
        "BAMBOO_BUTTON",
        "CRIMSON_BUTTON",
        "WARPED_BUTTON");

    private ToggleSourceClassifier() {
    }

    /**
     * Whether a block of the given material name is a manual toggle source
     * (lever or button).
     *
     * @param materialName the block's {@code Material.name()}; null yields false
     * @return true if the block is a lever or button
     */
    public static boolean isToggleSource(String materialName) {
        return materialName != null && TOGGLE_SOURCE_NAMES.contains(materialName);
    }
}
