package dev.waystone.vallhaloot.loot;

import java.util.*;

/**
 * A pool of loot entries with weighted selection.
 */
public class LootPool {
    private final String name;
    private final List<LootEntry> entries;
    private final int rolls;
    private final double rollBonus;

    public LootPool(String name, List<LootEntry> entries, int rolls, double rollBonus) {
        this.name = name;
        this.entries = new ArrayList<>(entries);
        this.rolls = Math.max(1, rolls);
        this.rollBonus = rollBonus;
    }

    public String getName() { return name; }
    public List<LootEntry> getEntries() { return Collections.unmodifiableList(entries); }
    public int getRolls() { return rolls; }
    public double getRollBonus() { return rollBonus; }

    /**
     * Pick a random entry from this pool based on weights.
     * Returns null if no entries apply to the context.
     */
    public LootEntry pickEntry(LootContext context, Random random) {
        List<LootEntry> applicable = new ArrayList<>();
        double[] weights = new double[entries.size()];
        double totalWeight = 0.0;

        for (int i = 0; i < entries.size(); i++) {
            LootEntry entry = entries.get(i);
            if (entry.applies(context, random)) {
                applicable.add(entry);
                weights[i] = entry.getWeight();
                totalWeight += weights[i];
            }
        }

        if (applicable.isEmpty() || totalWeight <= 0) {
            return null;
        }

        double pick = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (LootEntry entry : applicable) {
            cumulative += entry.getWeight();
            if (pick <= cumulative) {
                return entry;
            }
        }

        return applicable.get(applicable.size() - 1);
    }

    @Override
    public String toString() {
        return "LootPool{" + name + ", " + entries.size() + " entries, " + rolls + " rolls}";
    }
}
