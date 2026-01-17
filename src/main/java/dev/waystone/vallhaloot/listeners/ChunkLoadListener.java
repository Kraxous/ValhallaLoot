package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.storage.StorageManager;
import dev.waystone.vallhaloot.util.DebugLevel;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automatically converts vanilla containers in newly loaded chunks.
 * Runs asynchronously to avoid blocking the main thread.
 * 
 * MEMORY MANAGEMENT:
 * - Uses LinkedHashMap with LRU eviction for processedChunks (max 10,000 entries)
 * - Only tracks chunks from worlds configured for auto-conversion
 * - Prevents unbounded growth of processed chunk tracking
 */
public class ChunkLoadListener implements Listener {
    private final ValhallaLootPlugin plugin;
    private final StorageManager storage;
    
    // LRU cache with max 10,000 entries to prevent memory leaks
    private final Map<String, Boolean> processedChunks = Collections.synchronizedMap(
        new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 10000;
            }
        }
    );
    private final Set<Material> LOOT_CONTAINERS = Set.of(
        Material.CHEST, Material.TRAPPED_CHEST,
        Material.BARREL, Material.HOPPER,
        Material.DISPENSER, Material.DROPPER,
        Material.SHULKER_BOX, Material.BLACK_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
        Material.BROWN_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
        Material.GREEN_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX,
        Material.LIME_SHULKER_BOX, Material.MAGENTA_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
        Material.PINK_SHULKER_BOX, Material.PURPLE_SHULKER_BOX, Material.RED_SHULKER_BOX,
        Material.WHITE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX
    );

    private AtomicInteger backgroundConverted = new AtomicInteger(0);

    public ChunkLoadListener(@NotNull ValhallaLootPlugin plugin, @NotNull StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Check if background auto-conversion is enabled globally
        if (!plugin.getConfig().getBoolean("auto-convert-on-chunk-load", false)) {
            return;
        }

        String worldName = event.getWorld().getName();
        
        // Check if this world has been converted (only auto-convert in worlds that have already been converted)
        if (!storage.hasConvertedContainers(worldName)) {
            return;
        }
        
        // Check if this world is configured for auto-conversion
        if (!isWorldEnabledForConversion(worldName)) {
            return;
        }
        
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        String chunkKey = worldName + ":" + chunkX + ":" + chunkZ;

        // Skip if already processed (LRU evicts old entries automatically)
        if (processedChunks.containsKey(chunkKey)) {
            return;
        }

        // Mark as processed
        processedChunks.put(chunkKey, true);

        // Run conversion on next tick to avoid blocking chunk load, but stay on main thread to avoid async access issues
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                processChunk(event.getChunk(), worldName);
            } catch (Exception e) {
                plugin.debug(DebugLevel.HIGH, "Error processing chunk %s:%d,%d: %s", 
                    worldName, chunkX, chunkZ, e.getMessage());
            }
        });
    }
    
    /**
     * Check if a world is configured for auto-conversion.
     * Worlds can be enabled individually in config.
     */
    private boolean isWorldEnabledForConversion(@NotNull String worldName) {
        // Check world-specific config
        Object worldsConfig = plugin.getConfig().get("auto-convert-worlds");
        
        if (worldsConfig instanceof List<?>) {
            // Whitelist mode: only convert specified worlds
            List<?> worlds = (List<?>) worldsConfig;
            return worlds.stream().anyMatch(w -> w.toString().equalsIgnoreCase(worldName));
        } else if (worldsConfig instanceof String) {
            // Simple string mode: "all" or specific world name
            String config = (String) worldsConfig;
            return "all".equalsIgnoreCase(config) || config.equalsIgnoreCase(worldName);
        }
        
        // Default: convert all worlds if not specified
        return true;
    }

    private void processChunk(@NotNull org.bukkit.Chunk chunk, @NotNull String worldName) {
        BlockState[] tileEntities = chunk.getTileEntities();
        if (tileEntities.length == 0) {
            return;
        }

        NamespacedKey convertedKey = plugin.getConvertedKey();
        NamespacedKey playerPlacedKey = plugin.getPlayerPlacedKey();
        AtomicInteger converted = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        for (BlockState state : tileEntities) {
            if (!LOOT_CONTAINERS.contains(state.getType())) {
                continue;
            }

            if (!(state instanceof org.bukkit.block.TileState)) {
                continue;
            }

            org.bukkit.block.TileState tileState = (org.bukkit.block.TileState) state;
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();

            // Skip if player-placed (stored as BYTE by ContainerPlacementListener)
            if (pdc.has(playerPlacedKey, PersistentDataType.BYTE)) {
                skipped.incrementAndGet();
                continue;
            }

            // Skip if already converted
            if (pdc.has(convertedKey, PersistentDataType.INTEGER)) {
                skipped.incrementAndGet();
                continue;
            }

            // Convert this vanilla container (already on main thread)
            try {
                pdc.set(convertedKey, PersistentDataType.INTEGER, 1);

                // Backup original inventory
                if (state instanceof Container) {
                    Container container = (Container) state;
                    String containerKey = getContainerKey(state.getLocation());
                    String serialized = dev.waystone.vallhaloot.util.InventorySerializer.toBase64(
                        container.getInventory().getContents()
                    );
                    storage.saveOriginalInventory(containerKey, serialized);
                }

                tileState.update(true, false);
                converted.incrementAndGet();
                backgroundConverted.incrementAndGet();

                int total = backgroundConverted.get();
                if (total % 50 == 0) {
                    plugin.debug(DebugLevel.NORMAL, "Background conversion: %d containers converted so far", total);
                }
            } catch (Exception e) {
                plugin.debug(DebugLevel.HIGH, "Error converting container at %s: %s", 
                    state.getLocation(), e.getMessage());
            }
        }

        int totalConverted = converted.get();
        if (totalConverted > 0) {
            plugin.debug(DebugLevel.LOW, "Background: Chunk %s:%d,%d - converted %d containers", 
                worldName, chunk.getX(), chunk.getZ(), totalConverted);
        }
    }

    private String getContainerKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * Get the total number of containers converted in the background.
     */
    public int getBackgroundConversionCount() {
        return backgroundConverted.get();
    }

    /**
     * Reset the background conversion counter.
     * Note: processedChunks uses LRU eviction, so manual clearing is optional
     * but useful for fresh starts after server maintenance.
     */
    public void resetBackgroundConversionCount() {
        backgroundConverted.set(0);
        // Optional: clear old entries (LRU will auto-evict anyway)
        if (processedChunks.size() > 5000) {
            processedChunks.clear();
            plugin.debug(DebugLevel.LOW, "Cleared processed chunk cache (grew to %d entries)", 5000);
        }
    }
}
