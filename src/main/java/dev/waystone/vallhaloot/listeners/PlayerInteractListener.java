package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player right-click interactions with blocks.
 * Used to deduplicate with InventoryOpenEvent since both fire for container opens.
 * Keeps interaction timestamps to avoid double-processing within a small window.
 */
public class PlayerInteractListener implements Listener {
    private final ValhallaLootPlugin plugin;
    private final ConcurrentHashMap<String, Long> lastInteractionTime = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 250;

    public PlayerInteractListener(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // This listener exists for potential future use cases like respawning loot
        // For now, we rely on InventoryOpenEvent for the main flow.
        // If you need to trigger loot on block interact (e.g., without opening inventory),
        // implement that logic here.
    }
}
