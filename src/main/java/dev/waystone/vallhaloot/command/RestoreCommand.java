package dev.waystone.vallhaloot.command;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.storage.StorageManager;
import dev.waystone.vallhaloot.util.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RestoreCommand implements CommandExecutor {
    private final ValhallaLootPlugin plugin;
    private final StorageManager storage;

    private static final Set<Material> LOOT_CONTAINERS = Set.of(
        Material.CHEST,
        Material.BARREL,
        Material.SHULKER_BOX,
        Material.WHITE_SHULKER_BOX,
        Material.ORANGE_SHULKER_BOX,
        Material.MAGENTA_SHULKER_BOX,
        Material.LIGHT_BLUE_SHULKER_BOX,
        Material.YELLOW_SHULKER_BOX,
        Material.LIME_SHULKER_BOX,
        Material.PINK_SHULKER_BOX,
        Material.GRAY_SHULKER_BOX,
        Material.LIGHT_GRAY_SHULKER_BOX,
        Material.CYAN_SHULKER_BOX,
        Material.PURPLE_SHULKER_BOX,
        Material.BLUE_SHULKER_BOX,
        Material.BROWN_SHULKER_BOX,
        Material.GREEN_SHULKER_BOX,
        Material.RED_SHULKER_BOX,
        Material.BLACK_SHULKER_BOX,
        Material.TRAPPED_CHEST
    );

    public RestoreCommand(ValhallaLootPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("valloot.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /valloot restore <world_name|all> <confirm>");
            sender.sendMessage("§eThis will restore original container inventories and remove conversion marks.");
            return true;
        }

        String target = args[0].toLowerCase();
        String confirm = args[1].toLowerCase();
        
        if (!confirm.equals("confirm")) {
            sender.sendMessage("§cConfirmation required. Run: /valloot restore " + target + " confirm");
            return true;
        }

        if (target.equals("all")) {
            restoreAllWorlds(sender);
        } else {
            restoreWorld(sender, target);
        }
        
        return true;
    }

    private void restoreAllWorlds(@NotNull CommandSender sender) {
        sender.sendMessage("§eStarting restoration of all worlds...");
        sender.sendMessage("§eThis may take a while depending on container count.");
        
        AtomicInteger totalRestored = new AtomicInteger(0);
        var worlds = Bukkit.getWorlds();
        
        CompletableFuture.allOf(
            worlds.stream()
                .map(world -> restoreWorldAsync(world.getName())
                    .thenAccept(count -> totalRestored.addAndGet(count)))
                .toArray(CompletableFuture[]::new)
        ).thenRun(() -> {
            sender.sendMessage("§aRestoration complete!");
            sender.sendMessage("§eTotal containers restored: " + totalRestored.get());
            sender.sendMessage("§eServer will restart in 10 seconds...");
            scheduleRestart();
        });
    }

    private void restoreWorld(@NotNull CommandSender sender, String worldName) {
        if (Bukkit.getWorld(worldName) == null) {
            sender.sendMessage("§cWorld '" + worldName + "' not found.");
            return;
        }
        
        sender.sendMessage("§eStarting restoration of world: " + worldName);
        
        restoreWorldAsync(worldName).thenAccept(count -> {
            sender.sendMessage("§aRestoration complete!");
            sender.sendMessage("§eTotal containers restored: " + count);
            sender.sendMessage("§eServer will restart in 10 seconds...");
            scheduleRestart();
        });
    }

    private CompletableFuture<Integer> restoreWorldAsync(String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) return 0;
            
            int restored = 0;
            NamespacedKey convertedKey = plugin.getConvertedKey();
            
            // Iterate through loaded chunks and restore containers
            for (var chunk : world.getLoadedChunks()) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                            Block block = chunk.getBlock(x, y, z);
                            if (LOOT_CONTAINERS.contains(block.getType())) {
                                BlockState state = block.getState();
                                
                                if (state instanceof TileState) {
                                    TileState tileState = (TileState) state;
                                    PersistentDataContainer pdc = tileState.getPersistentDataContainer();
                                    
                                    // Check if this container was converted
                                    if (pdc.has(convertedKey, PersistentDataType.INTEGER)) {
                                        pdc.remove(convertedKey);
                                        
                                        // Restore original inventory if available
                                        if (state instanceof Container) {
                                            Container container = (Container) state;
                                            String containerKey = getContainerKey(block.getLocation());
                                            String serialized = storage.getOriginalInventory(containerKey);
                                            
                                            if (serialized != null) {
                                                try {
                                                    ItemStack[] items = InventorySerializer.fromBase64(serialized);
                                                    container.getInventory().setContents(items);
                                                } catch (Exception e) {
                                                    plugin.getLogger().warning("Failed to restore inventory at " + containerKey);
                                                }
                                            }
                                            
                                            // Clean up storage
                                            storage.removeOriginalInventory(containerKey);
                                        }
                                        
                                        tileState.update(true, false);
                                        restored++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return restored;
        });
    }

    private String getContainerKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void scheduleRestart() {
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            Bukkit.shutdown();
        }, 10 * 20); // 10 seconds in ticks
    }
}
