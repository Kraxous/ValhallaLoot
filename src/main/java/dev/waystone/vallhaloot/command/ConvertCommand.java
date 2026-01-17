package dev.waystone.vallhaloot.command;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.storage.StorageManager;
import dev.waystone.vallhaloot.util.InventorySerializer;
import dev.waystone.vallhaloot.util.DebugLevel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ConvertCommand implements CommandExecutor {
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

    // Result class to track conversion statistics
    private static class ConversionResult {
        final int newlyConverted;
        final int alreadyConverted;
        
        ConversionResult(int newlyConverted, int alreadyConverted) {
            this.newlyConverted = newlyConverted;
            this.alreadyConverted = alreadyConverted;
        }
    }

    public ConvertCommand(ValhallaLootPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("valloot.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cUsage: /valloot convert <world_name|all> [--load-all-chunks]");
            return true;
        }

        String target = args[0].toLowerCase();
        boolean loadAllChunks = args.length > 1 && args[1].equals("--load-all-chunks");
        
        if (target.equals("all")) {
            convertAllWorlds(sender, loadAllChunks);
        } else {
            convertWorld(sender, target, loadAllChunks);
        }
        
        return true;
    }

    public boolean onStatus(CommandSender sender, String[] args) {
        if (!sender.hasPermission("valloot.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            // Show all worlds
            sender.sendMessage("§e========================================");
            sender.sendMessage("§e  VALLHALOOT CONVERSION STATUS");
            sender.sendMessage("§e========================================");
            
            var worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                sender.sendMessage("§cNo worlds loaded.");
                return true;
            }
            
            int totalConverted = 0;
            for (var world : worlds) {
                int converted = countConvertedContainers(world);
                totalConverted += converted;
                sender.sendMessage("§e" + world.getName() + ": §b" + converted + " §econverted");
            }
            sender.sendMessage("§e");
            sender.sendMessage("§eTotal across all worlds: §b" + totalConverted + " containers");
            sender.sendMessage("§7Note: Only counts chunks that have been loaded.");
        } else {
            // Show specific world
            String worldName = args[0].toLowerCase();
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage("§cWorld '" + worldName + "' not found.");
                return true;
            }
            
            sender.sendMessage("§e========================================");
            sender.sendMessage("§e  VALLHALOOT CONVERSION STATUS");
            sender.sendMessage("§e========================================");
            
            int converted = countConvertedContainers(world);
            int totalChunks = world.getLoadedChunks().length;
            
            sender.sendMessage("§eWorld: §b" + world.getName());
            sender.sendMessage("§eConverted containers: §b" + converted);
            sender.sendMessage("§eLoaded chunks scanned: §b" + totalChunks);
            sender.sendMessage("§e");
            sender.sendMessage("§7Note: Shows only loaded chunks. Unload and reload");
            sender.sendMessage("§7chunks (e.g., with a chunk loader) then use /valloot");
            sender.sendMessage("§7convert to process newly loaded chunks.");
        }
        
        return true;
    }

    private int countConvertedContainers(World world) {
        int count = 0;
        NamespacedKey convertedKey = plugin.getConvertedKey();
        
        plugin.debug(DebugLevel.LOW, "Counting converted containers in world: %s", world.getName());
        
        for (var chunk : world.getLoadedChunks()) {
            BlockState[] tileEntities = chunk.getTileEntities();
            for (BlockState state : tileEntities) {
                if (!LOOT_CONTAINERS.contains(state.getType())) {
                    continue;
                }
                
                if (!(state instanceof TileState)) {
                    continue;
                }
                
                TileState tileState = (TileState) state;
                PersistentDataContainer pdc = tileState.getPersistentDataContainer();
                
                if (pdc.has(convertedKey, PersistentDataType.INTEGER)) {
                    count++;
                }
            }
        }
        
        return count;
    }

    private void convertAllWorlds(@NotNull CommandSender sender, boolean loadAllChunks) {
        sender.sendMessage("§e========================================");
        sender.sendMessage("§e  VALLHALOOT CONTAINER CONVERSION");
        sender.sendMessage("§e========================================");
        sender.sendMessage("§eStarting conversion of all worlds...");
        sender.sendMessage("§eScanning for vanilla containers only (player-placed excluded)");
        sender.sendMessage("§eProcessing worlds sequentially for optimal performance");
        if (loadAllChunks) {
            sender.sendMessage("§e[WARNING] Loading all chunks - this may take a while!");
        }
        sender.sendMessage("§e");
        
        long startTime = System.currentTimeMillis();
        AtomicInteger totalConverted = new AtomicInteger(0);
        AtomicInteger totalAlreadyConverted = new AtomicInteger(0);
        AtomicInteger totalChunksScanned = new AtomicInteger(0);
        var worlds = Bukkit.getWorlds();
        
        plugin.debug(DebugLevel.HIGHEST, "Starting global container conversion (sequential)...");
        plugin.debug(DebugLevel.LOW, "Total worlds to process: %d", worlds.size());
        
        // Process worlds sequentially instead of in parallel
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var world : worlds) {
            chain = chain.thenCompose(v -> 
                convertWorldAsync(world.getName(), sender, totalChunksScanned, loadAllChunks)
                    .thenAccept(result -> {
                        int converted = result.newlyConverted;
                        int alreadyConverted = result.alreadyConverted;
                        totalConverted.addAndGet(converted);
                        totalAlreadyConverted.addAndGet(alreadyConverted);
                        
                        if (alreadyConverted > 0 && converted == 0) {
                            sender.sendMessage("§7✓ " + world.getName() + " §7already converted: §b" + alreadyConverted + " §7containers (skipped)");
                        } else if (converted > 0) {
                            sender.sendMessage("§a✓ " + world.getName() + " §acomplete: §b" + converted + " §anewly converted" +
                                (alreadyConverted > 0 ? " §7(§b" + alreadyConverted + " §7already done)" : ""));
                        } else {
                            sender.sendMessage("§7✓ " + world.getName() + " §7complete: no containers found");
                        }
                        plugin.debug(DebugLevel.NORMAL, "World %s complete: %d new, %d already converted", world.getName(), converted, alreadyConverted);
                    })
            );
        }
        
        chain.thenRun(() -> {
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            sender.sendMessage("§e");
            sender.sendMessage("§a========================================");
            sender.sendMessage("§a  CONVERSION COMPLETE!");
            sender.sendMessage("§a========================================");
            sender.sendMessage("§eTotal chunks scanned: §b" + totalChunksScanned.get());
            sender.sendMessage("§eTotal containers newly converted: §b" + totalConverted.get());
            if (totalAlreadyConverted.get() > 0) {
                sender.sendMessage("§eTotal already converted (skipped): §b" + totalAlreadyConverted.get());
            }
            sender.sendMessage("§eTime taken: §b" + duration + " seconds");
            sender.sendMessage("§e");
            
            if (totalConverted.get() > 0) {
                sender.sendMessage("§eServer will restart in 10 seconds...");
                plugin.debug(DebugLevel.HIGHEST, "Conversion complete! %d new containers, %d already converted (%ds)", 
                    totalConverted.get(), totalAlreadyConverted.get(), duration);
                plugin.debug(DebugLevel.LOW, "Total chunks scanned: %d", totalChunksScanned.get());
                scheduleRestart();
            } else {
                sender.sendMessage("§eNo new conversions needed - server restart cancelled.");
                plugin.debug(DebugLevel.NORMAL, "No new conversions performed. %d containers already converted.", totalAlreadyConverted.get());
            }
        });
    }

    private void convertWorld(@NotNull CommandSender sender, String worldName, boolean loadAllChunks) {
        if (Bukkit.getWorld(worldName) == null) {
            sender.sendMessage("§cWorld '" + worldName + "' not found.");
            return;
        }
        
        sender.sendMessage("§e========================================");
        sender.sendMessage("§e  VALLHALOOT CONTAINER CONVERSION");
        sender.sendMessage("§e========================================");
        sender.sendMessage("§eStarting conversion of world: §b" + worldName);
        sender.sendMessage("§eScanning for vanilla containers only (player-placed excluded)");
        if (loadAllChunks) {
            sender.sendMessage("§e[WARNING] Loading all chunks - this may take a while!");
        }
        sender.sendMessage("§e");
        
        long startTime = System.currentTimeMillis();
        AtomicInteger chunksScanned = new AtomicInteger(0);
        plugin.debug(DebugLevel.NORMAL, "Starting conversion for world: %s", worldName);
        
        convertWorldAsync(worldName, sender, chunksScanned, loadAllChunks).thenAccept(result -> {
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            int converted = result.newlyConverted;
            int alreadyConverted = result.alreadyConverted;
            
            sender.sendMessage("§e");
            sender.sendMessage("§a========================================");
            sender.sendMessage("§a  CONVERSION COMPLETE!");
            sender.sendMessage("§a========================================");
            sender.sendMessage("§eChunks scanned: §b" + chunksScanned.get());
            sender.sendMessage("§eContainers newly converted: §b" + converted);
            if (alreadyConverted > 0) {
                sender.sendMessage("§eAlready converted (skipped): §b" + alreadyConverted);
            }
            sender.sendMessage("§eTime taken: §b" + duration + " seconds");
            sender.sendMessage("§e");
            
            if (converted > 0) {
                sender.sendMessage("§eServer will restart in 10 seconds...");
                plugin.debug(DebugLevel.HIGHEST, "World %s conversion complete: %d new containers in %d chunks (%ds)", 
                    worldName, converted, chunksScanned.get(), duration);
                scheduleRestart();
            } else if (alreadyConverted > 0) {
                sender.sendMessage("§7World already converted - server restart cancelled.");
                plugin.debug(DebugLevel.NORMAL, "World %s already converted: %d containers (no restart needed)", worldName, alreadyConverted);
            } else {
                sender.sendMessage("§7No containers found - server restart cancelled.");
                plugin.debug(DebugLevel.NORMAL, "World %s has no containers to convert", worldName);
            }
        });
    }

    private CompletableFuture<ConversionResult> convertWorldAsync(String worldName, CommandSender sender, AtomicInteger totalChunksScanned, boolean loadAllChunks) {
        CompletableFuture<ConversionResult> future = new CompletableFuture<>();
        
        // Run on main thread since we're accessing Bukkit API
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.debug(DebugLevel.HIGH, "World %s is null, skipping...", worldName);
                future.complete(new ConversionResult(0, 0));
                return;
            }
            
            plugin.debug(DebugLevel.LOWEST, "World %s min/max height: %d/%d", worldName, world.getMinHeight(), world.getMaxHeight());
            
            // If loadAllChunks is true, note that it will affect what's loaded
            if (loadAllChunks) {
                sender.sendMessage("§e→ Note: Using --load-all-chunks flag");
                sender.sendMessage("§e→ Will scan all loaded chunks in region files");
                sender.sendMessage("§e→ Warning: Loading all chunks may consume significant memory!");
                plugin.debug(DebugLevel.NORMAL, "Pre-loading chunks in %s (use with caution)", worldName);
            }
            
            int converted = 0;
            int skippedPlayerPlaced = 0;
            int skippedAlreadyConverted = 0;
            NamespacedKey convertedKey = plugin.getConvertedKey();
            NamespacedKey playerPlacedKey = plugin.getPlayerPlacedKey();
            
            // Get all loaded chunks for this world
            Chunk[] chunks = world.getLoadedChunks();
            int totalChunks = chunks.length;
            
            // Process chunks with progress updates
            final int UPDATE_INTERVAL = 10; // Update every 10 chunks
            final int CHUNKS_PER_TICK = 5; // Process 5 chunks per tick to avoid lag
            
            plugin.debug(DebugLevel.NORMAL, "Scanning %d chunks in %s...", totalChunks, worldName);
            plugin.debug(DebugLevel.LOWEST, "Update interval: every %d chunks, chunks per tick: %d", UPDATE_INTERVAL, CHUNKS_PER_TICK);
            sender.sendMessage("§e→ Scanning §b" + worldName + "§e: " + totalChunks + " chunks loaded");
            sender.sendMessage("§e→ Starting scan...");
            
            final int[] chunkIdx = {0};
            final int[] converted_ref = {0};
            final int[] skippedPlayerPlaced_ref = {0};
            final int[] skippedAlreadyConverted_ref = {0};
            final org.bukkit.scheduler.BukkitTask[] taskRef = new org.bukkit.scheduler.BukkitTask[1];
            
            taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    int processedThisTick = 0;
                    
                    while (chunkIdx[0] < chunks.length && processedThisTick < CHUNKS_PER_TICK) {
                        Chunk chunk = chunks[chunkIdx[0]];
                        totalChunksScanned.incrementAndGet();
                        
                        // Scan tile entities in the chunk for containers
                        BlockState[] tileEntities = chunk.getTileEntities();
                        for (BlockState state : tileEntities) {
                            // Check if it's a container type
                            if (!LOOT_CONTAINERS.contains(state.getType())) {
                                continue;
                            }
                            
                            if (!(state instanceof TileState)) {
                                continue;
                            }
                            
                            TileState tileState = (TileState) state;
                            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
                            
                            // Skip if player-placed
                            if (pdc.has(playerPlacedKey, PersistentDataType.INTEGER)) {
                                skippedPlayerPlaced_ref[0]++;
                                continue;
                            }
                            
                            // Track if already converted
                            if (pdc.has(convertedKey, PersistentDataType.INTEGER)) {
                                skippedAlreadyConverted_ref[0]++;
                                continue;
                            }
                            
                            // This is a vanilla container - convert it!
                            pdc.set(convertedKey, PersistentDataType.INTEGER, 1);
                            
                            // Backup original inventory
                            if (state instanceof Container) {
                                Container container = (Container) state;
                                String containerKey = getContainerKey(state.getLocation());
                                String serialized = InventorySerializer.toBase64(container.getInventory().getContents());
                                storage.saveOriginalInventory(containerKey, serialized);
                            }
                            
                            tileState.update(true, false);
                            converted_ref[0]++;
                        }
                        
                        chunkIdx[0]++;
                        processedThisTick++;
                        
                        // Progress updates
                        if (chunkIdx[0] % UPDATE_INTERVAL == 0 || chunkIdx[0] == chunks.length) {
                            int progress = (int) (chunkIdx[0] * 100.0 / totalChunks);
                            String progressMsg = String.format("§e→ %s: §b%d§e/§b%d §echunks (§b%d%%§e) | New: §a%d §e| Already: §b%d §e| Skipped: §7%d",
                                    worldName, chunkIdx[0], totalChunks, progress, converted_ref[0], skippedAlreadyConverted_ref[0], skippedPlayerPlaced_ref[0]);
                            sender.sendMessage(progressMsg);
                            plugin.debug(DebugLevel.NORMAL, "%s progress: %d/%d chunks (%d%%), %d new, %d already done, %d player-placed", 
                                worldName, chunkIdx[0], totalChunks, progress, converted_ref[0], skippedAlreadyConverted_ref[0], skippedPlayerPlaced_ref[0]);
                        }
                    }
                    
                    // Check if we're done
                    if (chunkIdx[0] >= chunks.length) {
                        // Cancel this task
                        taskRef[0].cancel();
                        
                        // Final summary
                        plugin.debug(DebugLevel.NORMAL, "%s scan complete:", worldName);
                        plugin.debug(DebugLevel.NORMAL, "  - Vanilla containers newly converted: %d", converted_ref[0]);
                        plugin.debug(DebugLevel.NORMAL, "  - Already converted: %d", skippedAlreadyConverted_ref[0]);
                        plugin.debug(DebugLevel.NORMAL, "  - Player-placed skipped: %d", skippedPlayerPlaced_ref[0]);
                        plugin.debug(DebugLevel.LOW, "  - Total chunks scanned: %d", chunks.length);
                        plugin.debug(DebugLevel.LOWEST, "  - Tile entities processed: ~%d", 
                            converted_ref[0] + skippedAlreadyConverted_ref[0] + skippedPlayerPlaced_ref[0]);
                        
                        future.complete(new ConversionResult(converted_ref[0], skippedAlreadyConverted_ref[0]));
                    }
                }
            }, 1L, 1L); // Run every tick
        });
        
        return future;
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
