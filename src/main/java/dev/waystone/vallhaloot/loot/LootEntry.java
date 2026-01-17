package dev.waystone.vallhaloot.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * A single item entry in a loot pool.
 * Describes an item type, amount, weight, and conditions.
 */
public class LootEntry {
    private final Material material;
    private final int minAmount;
    private final int maxAmount;
    private final double weight;
    private final String displayName;
    private final List<String> lore;
    private final List<LootCondition> conditions;
    private final boolean overwrite;

    public LootEntry(Material material, int minAmount, int maxAmount, double weight,
                    String displayName, List<String> lore, 
                    List<LootCondition> conditions, boolean overwrite) {
        this.material = material;
        this.minAmount = Math.max(1, minAmount);
        this.maxAmount = Math.max(this.minAmount, maxAmount);
        this.weight = weight;
        this.displayName = displayName;
        this.lore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        this.conditions = conditions != null ? new ArrayList<>(conditions) : new ArrayList<>();
        this.overwrite = overwrite;
    }

    public Material getMaterial() { return material; }
    public int getMinAmount() { return minAmount; }
    public int getMaxAmount() { return maxAmount; }
    public double getWeight() { return weight; }
    public String getDisplayName() { return displayName; }
    public List<String> getLore() { return Collections.unmodifiableList(lore); }
    public List<LootCondition> getConditions() { return Collections.unmodifiableList(conditions); }
    public boolean isOverwrite() { return overwrite; }

    /**
     * Check if this entry applies given the context.
     */
    public boolean applies(LootContext context, Random random) {
        for (LootCondition condition : conditions) {
            if (!condition.test(context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get a random amount between min and max.
     */
    public int getRandomAmount(Random random) {
        if (minAmount == maxAmount) {
            return minAmount;
        }
        return minAmount + random.nextInt(maxAmount - minAmount + 1);
    }

    @Override
    public String toString() {
        return "LootEntry{" + material + " x" + minAmount + "-" + maxAmount + 
               " (weight=" + weight + ")}";
    }
}
