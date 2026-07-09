package org.nebula.entity;

/**
 * Maps a Minecraft block-type name to the {@link BlockEntityTaskType} that ticks it
 * autonomously — or {@code null} for a block that participates in item transfer only
 * as a read/write <em>target</em> and never seeds a task of its own (a chest, barrel,
 * shulker box, hopper-minecart target, …).
 *
 * <p>This is the pure, Bukkit-free core of the block-entity seed path (B8 C3). The
 * live {@code InventoryMoveItemEvent} listener sees both endpoints of a transfer, but
 * only the autonomously-ticking endpoint should be seeded as a DAG task: a hopper
 * pushing into a chest is the <em>hopper's</em> tick, and the chest is merely the
 * hopper output slots' write target — modelling the chest as its own ticking task is a
 * lie about what the DAG ticks. The prior bring-up (ce6abf2) stamped BOTH endpoints as
 * {@code HOPPER}, so a chest destination got a fictional hopper RW-set and a
 * dropper/dispenser/furnace got the wrong type. This classifier closes that gap.
 *
 * <p>Keyed on {@code org.bukkit.Material#name()} (e.g. {@code "HOPPER"},
 * {@code "BLAST_FURNACE"}) so it stays in nebula-entity with no Bukkit dependency and
 * is unit-provable. The three furnace variants (furnace / blast furnace / smoker) all
 * map to {@link BlockEntityTaskType#FURNACE} because they share the input/fuel/output
 * smelting RW-set. Case-insensitive and tolerant of a {@code minecraft:} namespace
 * prefix, so either a Bukkit material name or a namespaced block id resolves.
 */
public final class BlockEntityClassifier {

    private BlockEntityClassifier() {}

    /**
     * @param materialName a Bukkit {@code Material.name()} or a (optionally
     *                     {@code minecraft:}-namespaced) block id; may be {@code null}
     * @return the ticking task type for this block, or {@code null} if the block does
     *         not autonomously tick as a modelled block entity (skip seeding it)
     */
    public static BlockEntityTaskType classify(String materialName) {
        if (materialName == null) return null;
        String key = materialName.trim().toUpperCase(java.util.Locale.ROOT);
        int colon = key.indexOf(':');
        if (colon >= 0) key = key.substring(colon + 1);
        return switch (key) {
            case "HOPPER" -> BlockEntityTaskType.HOPPER;
            case "DROPPER" -> BlockEntityTaskType.DROPPER;
            case "DISPENSER" -> BlockEntityTaskType.DISPENSER;
            case "FURNACE", "BLAST_FURNACE", "SMOKER" -> BlockEntityTaskType.FURNACE;
            case "BREWING_STAND" -> BlockEntityTaskType.BREWING_STAND;
            default -> null;
        };
    }
}
