package org.nebula.player.actions;

import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import org.nebula.player.ItemStack;
import org.nebula.player.PlayerInventoryState;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Player eat action. Reads held item, checks if it's food.
 * Writes hunger, saturation, held item count (decremented).
 *
 * Food data is hardcoded for MVP. Real implementation reads from data packs.
 */
public final class PlayerEatAction implements PlayerTaskAction {

    private final UUID playerId;
    private final PlayerInventoryState inventory;

    public PlayerEatAction(UUID playerId, PlayerInventoryState inventory) {
        this.playerId = playerId;
        this.inventory = inventory;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        ItemStack held = inventory.getHeldItem(playerId);
        if (held.isEmpty()) return;

        FoodData food = FOOD_DATA.get(held.material());
        if (food == null) return;

        // Restore hunger and saturation
        ctx.writeScalar(playerId, "hunger",
            Math.min(20, ctx.readScalar(playerId, "hunger") + food.hungerRestore));
        ctx.writeScalar(playerId, "saturation",
            Math.min(ctx.readScalar(playerId, "hunger"),
                ctx.readScalar(playerId, "saturation") + food.saturationRestore));

        // Exhaustion
        ctx.writeScalar(playerId, "exhaustion",
            ctx.readScalar(playerId, "exhaustion") + food.exhaustionRestore);

        // Consume one food item
        inventory.consumeHeldItem(playerId);
    }

    // Food data: material → (hunger, saturation, exhaustion, eatTime)
    public record FoodData(double hungerRestore, double saturationRestore, double exhaustionRestore, int eatTicks) {}

    private static final Map<String, FoodData> FOOD_DATA = new java.util.HashMap<>();
    static {
        FOOD_DATA.put("APPLE", new FoodData(4, 2.4, 4.0, 32));
        FOOD_DATA.put("GOLDEN_APPLE", new FoodData(4, 9.6, 4.0, 32));
        FOOD_DATA.put("BREAD", new FoodData(5, 6.0, 5.0, 32));
        FOOD_DATA.put("COOKED_BEEF", new FoodData(8, 12.8, 6.0, 32));
        FOOD_DATA.put("RAW_BEEF", new FoodData(3, 1.8, 3.0, 32));
        FOOD_DATA.put("COOKED_CHICKEN", new FoodData(6, 12.6, 5.0, 32));
        FOOD_DATA.put("RAW_CHICKEN", new FoodData(2, 1.2, 2.0, 16)); // can poison
        FOOD_DATA.put("CARROT", new FoodData(3, 3.6, 4.0, 32));
        FOOD_DATA.put("GOLDEN_CARROT", new FoodData(6, 14.4, 5.0, 32));
        FOOD_DATA.put("POTATO", new FoodData(1, 0.6, 1.0, 32));
        FOOD_DATA.put("BAKED_POTATO", new FoodData(5, 6.0, 5.0, 32));
        FOOD_DATA.put("MELON_SLICE", new FoodData(2, 1.2, 2.0, 16));
        FOOD_DATA.put("COOKIE", new FoodData(2, 0.4, 2.0, 16));
        FOOD_DATA.put("PUMPKIN_PIE", new FoodData(4, 4.8, 5.0, 32));
        FOOD_DATA.put("BEETROOT", new FoodData(1, 1.2, 1.0, 32));
        FOOD_DATA.put("DRIED_KELP", new FoodData(1, 0.6, 1.0, 16));
        FOOD_DATA.put("SWEET_BERRIES", new FoodData(2, 1.2, 2.0, 16));
        FOOD_DATA.put("GLOW_BERRIES", new FoodData(2, 1.2, 2.0, 16));
        FOOD_DATA.put("CHORUS_FRUIT", new FoodData(4, 2.4, 4.0, 32));
        FOOD_DATA.put("ROTTEN_FLESH", new FoodData(4, 0.8, 3.0, 32)); // can poison
        FOOD_DATA.put("SPIDER_EYE", new FoodData(2, 3.6, 8.0, 32)); // poison
        FOOD_DATA.put("POISONOUS_POTATO", new FoodData(2, 1.2, 1.0, 32)); // poison
    }
}
