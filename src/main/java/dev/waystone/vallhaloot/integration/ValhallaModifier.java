package dev.waystone.vallhaloot.integration;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.LootContext;
import dev.waystone.vallhaloot.loot.LootModifier;
import dev.waystone.vallhaloot.loot.LootRollResult;
import dev.waystone.vallhaloot.util.DebugLevel;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;

/**
 * ValhallaMMO loot modifier.
 * When ValhallaMMO is available, this applies skill-based modifications to loot.
 * 
 * Future improvements:
 * - Read ValhallaMMO source or reverse-engineer public APIs
 * - Apply luck modifiers based on Luck stat
 * - Add extra loot pool entries for high-skill players
 * - Apply rarity multipliers based on perks
 * - Respect enchantment reroll chances from ValhallaMMO
 */
public class ValhallaModifier implements LootModifier {
    private final ValhallaLootPlugin plugin;
    private final Plugin valhallaPlugin;
    private final Player player;
    private final LootContext context;

    public ValhallaModifier(ValhallaLootPlugin plugin, Plugin valhallaPlugin) {
        this.plugin = plugin;
        this.valhallaPlugin = valhallaPlugin;
        this.player = null;
        this.context = null;
    }

    private ValhallaModifier(ValhallaLootPlugin plugin, Plugin valhallaPlugin, Player player, LootContext context) {
        this.plugin = plugin;
        this.valhallaPlugin = valhallaPlugin;
        this.player = player;
        this.context = context;
    }

    /**
     * Create a player-specific modifier.
     */
    public LootModifier createPlayerModifier(Player player, LootContext context) {
        return new ValhallaModifier(plugin, valhallaPlugin, player, context);
    }

    @Override
    public void modify(LootRollResult result) {
        if (player == null || context == null) {
            return;
        }
        // Soft-call ValhallaTrinkets default trinkets and ValhallaMMO custom items instead of duplicating tables
        try {
            // Add a trinket based on table type if ValhallaTrinkets is present
            addTrinketIfAvailable(result);
            // Future: add ValhallaMMO custom items similarly (soft dependency via reflection)
        } catch (Exception e) {
            plugin.debug(DebugLevel.LOW, "VALHALLA MODIFIER: Integration failed %s", e.getMessage());
        }
    }

    private void addTrinketIfAvailable(LootRollResult result) {
        Plugin trinkets = plugin.getServer().getPluginManager().getPlugin("ValhallaTrinkets");
        if (trinkets == null || !trinkets.isEnabled()) return;

        String table = result.getTableName();
        if (table == null) return;

        // Simple per-table chance, avoids recreating loot tables locally
        double chance;
        switch (table.toLowerCase(Locale.ROOT)) {
            case "trial_chambers":
                chance = 0.15; // 15%
                break;
            case "end_city":
                chance = 0.10; // 10%
                break;
            case "ancient_city":
                chance = 0.08; // 8%
                break;
            case "pillager_outpost":
                chance = 0.12; // 12%
                break;
            default:
                chance = 0.0; // not applicable
        }

        if (chance <= 0.0) return;

        if (Math.random() < chance) {
            ItemStack trinket = ValhallaTrinketsBridge.randomDefaultTrinket(plugin);
            if (trinket != null) {
                result.addItem(trinket);
                plugin.debug(DebugLevel.LOW, "VALHALLA MODIFIER: Added default trinket to %s", table);
            }
        }
    }
}
