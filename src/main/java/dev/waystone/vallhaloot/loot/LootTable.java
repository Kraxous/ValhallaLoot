package dev.waystone.vallhaloot.loot;

import java.util.*;

/**
 * Complete loot table with multiple pools.
 */
public class LootTable {
    private final String name;
    private final List<LootPool> pools;
    private final boolean firstOpenOnly;
    private final long respawnCooldownMs;
    private final double respawnVariance; // percentage variance

    public LootTable(String name, List<LootPool> pools, boolean firstOpenOnly,
                    long respawnCooldownMs, double respawnVariance) {
        this.name = name;
        this.pools = new ArrayList<>(pools);
        this.firstOpenOnly = firstOpenOnly;
        this.respawnCooldownMs = respawnCooldownMs;
        this.respawnVariance = respawnVariance;
    }

    public String getName() { return name; }
    public List<LootPool> getPools() { return Collections.unmodifiableList(pools); }
    public boolean isFirstOpenOnly() { return firstOpenOnly; }
    public long getRespawnCooldownMs() { return respawnCooldownMs; }
    public double getRespawnVariance() { return respawnVariance; }

    /**
     * Calculate actual respawn cooldown with variance.
     */
    public long getActualRespawnCooldown(Random random) {
        if (respawnVariance <= 0) {
            return respawnCooldownMs;
        }
        double variance = 1.0 - (respawnVariance / 100.0) + (random.nextDouble() * respawnVariance / 50.0);
        return (long) (respawnCooldownMs * variance);
    }

    @Override
    public String toString() {
        return "LootTable{" + name + ", " + pools.size() + " pools, firstOpen=" + firstOpenOnly + "}";
    }
}
