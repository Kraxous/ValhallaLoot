package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Listens for player right-click interactions with blocks.
 * Currently unused but kept for potential future enhancements.
 */
public class PlayerInteractListener implements Listener {
    @SuppressWarnings("unused")
    private final ValhallaLootPlugin plugin;

    public PlayerInteractListener(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Reserved for future use cases like manual loot respawning
    }
}
