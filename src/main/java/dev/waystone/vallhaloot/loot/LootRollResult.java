package dev.waystone.vallhaloot.loot;

import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Result of a loot roll: list of items to be placed in a container.
 */
public class LootRollResult {
    private final List<ItemStack> items;
    private final String tableName;
    private final long rollTimeMs;
    private final LootContext context;

    public LootRollResult(String tableName, List<ItemStack> items, long rollTimeMs, LootContext context) {
        this.tableName = tableName;
        this.items = new ArrayList<>(items);
        this.rollTimeMs = rollTimeMs;
        this.context = context;
    }

    public String getTableName() { return tableName; }
    public List<ItemStack> getItems() { return Collections.unmodifiableList(items); }
    public long getRollTimeMs() { return rollTimeMs; }
    public LootContext getContext() { return context; }

    @Override
    public String toString() {
        return "LootRollResult{" + tableName + ", " + items.size() + " items, " + rollTimeMs + "ms}";
    }
}
