package org.nebula.player;

import java.util.*;

/**
 * Minecraft crafting system. Handles:
 * - 3x3 shaped crafting (crafting table)
 * - 2x2 shapeless crafting (inventory crafting)
 * - Furnace smelting
 *
 * Recipe data is loaded from a static map. Real implementation would read
 * from data packs, but for the MVP we use a hardcoded recipe table.
 */
public final class CraftingSystem {

    public record Recipe(String output, int outputCount, List<String> shape, Map<Character, String> ingredients) {}
    public record SmeltingRecipe(String input, String output, float xp) {}

    private static final Map<String, Recipe> SHAPED_RECIPES = new HashMap<>();
    private static final Map<List<String>, Recipe> SHAPELESS_RECIPES = new HashMap<>();
    private static final Map<String, SmeltingRecipe> SMELTING_RECIPES = new HashMap<>();

    static {
        // Shaped recipes (key = output material)
        // Stick: 2 planks vertically
        addShaped("STICK", 4, List.of("S", "S"), Map.of('S', "PLANKS"));
        // Wooden planks: 1 log → 4 planks
        addShaped("PLANKS", 4, List.of("L"), Map.of('L', "LOG"));
        // Torch: 1 coal + 1 stick
        addShaped("TORCH", 4, List.of("C", "S", "S"), Map.of('C', "COAL", 'S', "STICK"));
        // Crafting table: 4 planks in 2x2
        addShaped("CRAFTING_TABLE", 1, List.of("PP", "PP"), Map.of('P', "PLANKS"));
        // Furnace: 8 cobblestone
        addShaped("FURNACE", 1, List.of("CCC", "C C", "CCC"), Map.of('C', "COBBLESTONE"));

        // Shapeless: any pattern of 3 wheat = bread
        addShapeless("BREAD", 1, List.of("WHEAT", "WHEAT", "WHEAT"));

        // Smelting
        addSmelting("IRON_ORE", "IRON_INGOT", 0.7f);
        addSmelting("GOLD_ORE", "GOLD_INGOT", 1.0f);
        addSmelting("DIRT", "COBBLESTONE", 0.1f);
        addSmelting("COBBLESTONE", "STONE", 0.1f);
        addSmelting("LOG", "CHARCOAL", 0.15f);
        addSmelting("SAND", "GLASS", 0.1f);
    }

    private static void addShaped(String output, int count, List<String> shape, Map<Character, String> ingredients) {
        SHAPED_RECIPES.put(output, new Recipe(output, count, shape, ingredients));
    }

    private static void addShapeless(String output, int count, List<String> ingredients) {
        SHAPELESS_RECIPES.put(ingredients, new Recipe(output, count, List.of(), Map.of()));
    }

    private static void addSmelting(String input, String output, float xp) {
        SMELTING_RECIPES.put(input, new SmeltingRecipe(input, output, xp));
    }

    /** Checks if the player's crafting grid matches a shaped recipe. Returns the output, or null. */
    public Recipe findShapedMatch(PlayerInventoryState inventory, UUID playerId,
                                  List<String> shape, Map<Character, String> ingredients) {
        // For MVP: check if all ingredient slots have the required material
        for (var e : ingredients.entrySet()) {
            if (!hasIngredient(inventory, playerId, e.getValue(), countNeeded(shape, ingredients, e.getKey()))) {
                return null;
            }
        }
        return SHAPED_RECIPES.values().stream()
            .filter(r -> r.shape().equals(shape))
            .findFirst().orElse(null);
    }

    /** Consumes ingredients from the crafting grid and gives the output. */
    public boolean craft(PlayerInventoryState inventory, UUID playerId, Recipe recipe) {
        // Remove ingredients
        for (var e : recipe.ingredients().entrySet()) {
            int needed = countNeeded(recipe.shape(), recipe.ingredients(), e.getKey());
            if (!consumeIngredient(inventory, playerId, e.getValue(), needed)) {
                return false; // not enough
            }
        }
        // Add output
        ItemStack result = new ItemStack(recipe.output(), recipe.outputCount());
        return inventory.addItem(playerId, result);
    }

    /** Smelts one item. Returns the output ItemStack or EMPTY. */
    public ItemStack smelt(PlayerInventoryState inventory, UUID playerId, int inputSlot) {
        ItemStack input = inventory.getSlot(playerId, inputSlot);
        if (input.isEmpty()) return ItemStack.EMPTY;
        SmeltingRecipe recipe = SMELTING_RECIPES.get(input.material());
        if (recipe == null) return ItemStack.EMPTY;

        // Consume one input
        inventory.setSlot(playerId, inputSlot, input.decrement());
        // Give output
        ItemStack output = new ItemStack(recipe.output(), 1);
        inventory.addItem(playerId, output);
        return output;
    }

    private boolean hasIngredient(PlayerInventoryState inventory, UUID playerId, String material, int needed) {
        int have = 0;
        for (int i = 0; i < PlayerInventoryState.TOTAL_SIZE; i++) {
            ItemStack s = inventory.getSlot(playerId, i);
            if (!s.isEmpty() && s.material().equals(material)) have += s.count();
        }
        return have >= needed;
    }

    private boolean consumeIngredient(PlayerInventoryState inventory, UUID playerId, String material, int needed) {
        int remaining = needed;
        for (int i = 0; i < PlayerInventoryState.TOTAL_SIZE && remaining > 0; i++) {
            ItemStack s = inventory.getSlot(playerId, i);
            if (!s.isEmpty() && s.material().equals(material)) {
                int take = Math.min(remaining, s.count());
                inventory.setSlot(playerId, i, s.withCount(s.count() - take));
                remaining -= take;
            }
        }
        return remaining == 0;
    }

    private int countNeeded(List<String> shape, Map<Character, String> ingredients, char c) {
        int count = 0;
        for (String row : shape) {
            for (char ch : row.toCharArray()) {
                if (ch == c) count++;
            }
        }
        return count;
    }

    public static Map<String, Recipe> shapedRecipes() { return SHAPED_RECIPES; }
    public static Map<List<String>, Recipe> shapelessRecipes() { return SHAPELESS_RECIPES; }
    public static Map<String, SmeltingRecipe> smeltingRecipes() { return SMELTING_RECIPES; }
}
