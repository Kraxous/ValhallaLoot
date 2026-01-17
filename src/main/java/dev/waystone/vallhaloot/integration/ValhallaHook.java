package dev.waystone.vallhaloot.integration;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.LootContext;
import dev.waystone.vallhaloot.loot.LootModifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Integration layer for ValhallaMMO.
 * Gracefully handles the absence of ValhallaMMO and provides sensible defaults.
 * 
 * This class detects whether ValhallaMMO is installed and enabled,
 * and provides hooks to modify loot based on player skills, perks, and other mechanics.
 */
public class ValhallaHook {
    private final ValhallaLootPlugin plugin;
    private final boolean available;
    private final ValhallaModifier valhallaModifier;
    private final LootModifier noopModifier;

    public ValhallaHook(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
        Plugin valhallaPlugin = Bukkit.getPluginManager().getPlugin("ValhallaMMO");
        this.available = valhallaPlugin != null && valhallaPlugin.isEnabled();
        
        if (this.available) {
            plugin.getLogger().info("ValhallaMMO detected, initializing integration...");
            this.valhallaModifier = new ValhallaModifier(plugin, valhallaPlugin);
            this.noopModifier = null;
        } else {
            plugin.getLogger().info("ValhallaMMO not found, using standalone mode");
            this.valhallaModifier = null;
            this.noopModifier = new NoopValhallaModifier();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Get a loot modifier for the given player context.
     * Returns a modifier that applies ValhallaMMO-based changes (skills, perks, etc).
     * Returns a NOOP modifier if ValhallaMMO is not available.
     */
    public LootModifier getModifier(Player player, LootContext context) {
        if (!available) {
            return noopModifier;
        }
        return valhallaModifier.createPlayerModifier(player, context);
    }

    /**
     * Enrich a loot context with ValhallaMMO data.
     * Loads player skills, perks, stats, and other relevant info into the context metadata.
     * Safe to call from any thread, but try to avoid calling frequently from async.
     */
    public Map<String, Object> enrichContext(Player player, LootContext baseContext) {
        Map<String, Object> metadata = new HashMap<>();
        
        if (!available) {
            return metadata;
        }

        // Attempt to load ValhallaMMO data
        // This would require access to ValhallaMMO APIs or event systems
        // For now, we scaffold the structure that would be filled in
        
        try {
            // These would be actual calls to ValhallaMMO API
            // e.g., valhallaAPI.getPlayerSkills(player)
            // For now, we'll rely on the ValhallaModifier to fill these in
            
            metadata.put("valhalla_available", true);
            metadata.put("player_obj", player);
        } catch (Exception e) {
            plugin.getLogger().warning("Error enriching context with ValhallaMMO data: " + e.getMessage());
        }

        return metadata;
    }
}
