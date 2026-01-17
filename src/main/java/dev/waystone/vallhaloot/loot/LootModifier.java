package dev.waystone.vallhaloot.loot;

/**
 * Interface for loot modification by external sources (e.g., ValhallaMMO).
 * Implementation should be thread-safe or only called from sync context.
 */
public interface LootModifier {
    /**
     * Modify the loot roll result based on player stats, skills, perks, etc.
     * Can add items, remove items, apply luck multipliers, etc.
     * 
     * @param result The loot roll result to modify
     */
    void modify(LootRollResult result);
}
