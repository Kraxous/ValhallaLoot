package dev.waystone.vallhaloot.loot;

import dev.waystone.vallhaloot.util.ItemStackBuilder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Pure loot computation engine.
 * Thread-safe: contains no Bukkit API calls, only data processing.
 * Can be called safely from async context.
 */
public class LootEngine {
    private static final Random RANDOM = new Random();

    /**
     * Roll loot from a table with a given context.
     * This is safe to call from any thread.
     * 
     * @param table The loot table to roll from
     * @param context The loot context (player, location, conditions)
     * @return A list of items to be placed in the container
     */
    public static LootRollResult roll(LootTable table, LootContext context) {
        long startTime = System.currentTimeMillis();
        List<ItemStack> items = new ArrayList<>();

        if (table == null) {
            return new LootRollResult(null, items, System.currentTimeMillis() - startTime, context);
        }

        // Roll each pool
        for (LootPool pool : table.getPools()) {
            int rolls = (int) (pool.getRolls() + pool.getRollBonus());
            for (int i = 0; i < rolls; i++) {
                LootEntry entry = pool.pickEntry(context, RANDOM);
                if (entry != null) {
                    ItemStack item = buildItemStack(entry);
                    items.add(item);
                }
            }
        }

        long rollTime = System.currentTimeMillis() - startTime;
        return new LootRollResult(table.getName(), items, rollTime, context);
    }

    /**
     * Build an ItemStack from a LootEntry.
     * Safe for async use (no Bukkit API calls required).
     */
    private static ItemStack buildItemStack(LootEntry entry) {
        int amount = entry.getRandomAmount(RANDOM);
        
        ItemStackBuilder builder = new ItemStackBuilder(entry.getMaterial(), amount);
        
        if (entry.getDisplayName() != null && !entry.getDisplayName().isEmpty()) {
            builder.withName(entry.getDisplayName());
        }
        
        if (!entry.getLore().isEmpty()) {
            builder.withLore(entry.getLore());
        }

        return builder.build();
    }

    /**
     * Modify loot rolls based on external factors (e.g., ValhallaMMO skills).
     * Can apply multipliers, add extra rolls, or reroll items.
     */
    public static void applyModifier(LootRollResult result, LootModifier modifier) {
        if (modifier != null) {
            modifier.modify(result);
        }
    }
}
