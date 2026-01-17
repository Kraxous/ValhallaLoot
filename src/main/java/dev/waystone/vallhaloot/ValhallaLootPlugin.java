package dev.waystone.vallhaloot;

import dev.waystone.vallhaloot.config.ConfigManager;
import dev.waystone.vallhaloot.integration.ValhallaHook;
import dev.waystone.vallhaloot.listeners.ContainerOpenListener;
import dev.waystone.vallhaloot.listeners.PlayerInteractListener;
import dev.waystone.vallhaloot.listeners.ChunkLoadListener;
import dev.waystone.vallhaloot.command.LootCommand;
import dev.waystone.vallhaloot.util.SchedulerHelper;
import dev.waystone.vallhaloot.util.DebugLevel;
import dev.waystone.vallhaloot.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;

/**
 * ValhallaLoot Plugin
 * 
 * Custom loot generation for containers and mobs, integrated with ValhallaMMO.
 * Thread-safe: all heavy computation is async, Bukkit API access is sync-only.
 * 
 * WHY NO DEADLOCKS:
 * - Never call .join() or .get() on main thread
 * - Async results are processed via runTask() callbacks
 * - Concurrent requests to same container are coalesced with CompletableFuture
 * - No blocking locks held during Bukkit API access
 * - SchedulerHelper ensures proper thread context for each operation
 */
public class ValhallaLootPlugin extends JavaPlugin {
    private static ValhallaLootPlugin instance;
    
    private ConfigManager configManager;
    private ValhallaHook valhallaHook;
    private StorageManager storageManager;
    private SchedulerHelper schedulerHelper;
    private ChunkLoadListener chunkLoadListener;
    private DebugLevel debugLevel = DebugLevel.NORMAL;
    private NamespacedKey playerPlacedKey;
    private NamespacedKey convertedKey;

    @Override
    public void onEnable() {
        instance = this;
        
        // Initialize scheduler first
        this.schedulerHelper = new SchedulerHelper(this);
        
        // Load configuration
        saveDefaultConfig();
        // Ensure default loot tables are available on first run
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            java.io.File tablesDir = new java.io.File(getDataFolder(), "tables");
            if (!tablesDir.exists()) {
                tablesDir.mkdirs();
            }
            java.io.File common = new java.io.File(tablesDir, "common.yml");
            java.io.File rare = new java.io.File(tablesDir, "rare.yml");
            if (!common.exists()) {
                saveResource("tables/common.yml", false);
            }
            if (!rare.exists()) {
                saveResource("tables/rare.yml", false);
            }
        } catch (Exception e) {
            getLogger().warning("Could not save default loot tables: " + e.getMessage());
        }
        this.configManager = new ConfigManager(this);
        if (!configManager.loadConfig()) {
            getLogger().warning("Failed to load configuration, using defaults");
        }
        
        // Load debug level from config
        String debugLevelStr = getConfig().getString("debug-level", "NORMAL");
        this.debugLevel = DebugLevel.fromString(debugLevelStr);
        debug(DebugLevel.NORMAL, "Debug level set to: %s", debugLevel.name());
        
        // Initialize storage (SQLite fallback for first-open tracking)
        this.storageManager = new StorageManager(this);
        
        // Initialize ValhallaMMO integration (gracefully degrades if not present)
        this.valhallaHook = new ValhallaHook(this);
        // PersistentData keys
        this.playerPlacedKey = new NamespacedKey(this, "player-placed");
        this.convertedKey = new NamespacedKey(this, "converted");
        
        // Register listeners
        Bukkit.getPluginManager().registerEvents(new ContainerOpenListener(this), this);
        Bukkit.getPluginManager().registerEvents(new dev.waystone.vallhaloot.listeners.ContainerPlacementListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerInteractListener(this), this);
        
        // Register chunk load listener for background container conversion
        this.chunkLoadListener = new ChunkLoadListener(this, storageManager);
        Bukkit.getPluginManager().registerEvents(chunkLoadListener, this);
        if (getConfig().getBoolean("auto-convert-on-chunk-load", false)) {
            debug(DebugLevel.NORMAL, "Background chunk-load container conversion ENABLED");
        }
        
        // Register command handler
        getCommand("valloot").setExecutor(new LootCommand(this, storageManager));
        
        getLogger().info("ValhallaLoot enabled successfully");
        if (valhallaHook.isAvailable()) {
            getLogger().info("ValhallaMMO integration available");
        } else {
            getLogger().info("ValhallaMMO not detected - running in standalone mode");
        }
    }

    @Override
    public void onDisable() {
        if (storageManager != null) {
            storageManager.shutdown();
        }
        getLogger().info("ValhallaLoot disabled");
    }

    public static ValhallaLootPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ValhallaHook getValhallaHook() {
        return valhallaHook;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public SchedulerHelper getSchedulerHelper() {
        return schedulerHelper;
    }

    public DebugLevel getDebugLevel() {
        return debugLevel;
    }

    public void setDebugLevel(DebugLevel level) {
        this.debugLevel = level;
        debug(DebugLevel.HIGHEST, "Debug level set to: " + level.name());
    }
    
    /**
     * Log a debug message if the current debug level allows it.
     */
    public void debug(DebugLevel level, String message) {
        if (level.shouldLog(debugLevel)) {
            getLogger().info("[" + level.getPrefix() + "] " + message);
        }
    }
    
    /**
     * Log a debug message with format args if the current debug level allows it.
     */
    public void debug(DebugLevel level, String format, Object... args) {
        if (level.shouldLog(debugLevel)) {
            getLogger().info("[" + level.getPrefix() + "] " + String.format(format, args));
        }
    }

    public NamespacedKey getPlayerPlacedKey() {
        return playerPlacedKey;
    }

    public NamespacedKey getConvertedKey() {
        return convertedKey;
    }

    public ChunkLoadListener getChunkLoadListener() {
        return chunkLoadListener;
    }
}
