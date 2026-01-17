package dev.waystone.vallhaloot.config;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and validates loot table configurations from YAML files.
 */
public class ConfigManager {
    private final ValhallaLootPlugin plugin;
    private final Map<String, LootTable> lootTables = new ConcurrentHashMap<>();
    private ConfigurationSection mainConfig;
    private boolean perPlayerLoot;

    public ConfigManager(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean loadConfig() {
        try {
            // Load main config
            plugin.getConfig().load(new File(plugin.getDataFolder(), "config.yml"));
            this.mainConfig = plugin.getConfig();

            // Read global flags
            ConfigurationSection containers = mainConfig.getConfigurationSection("containers");
            if (containers != null) {
                this.perPlayerLoot = containers.getBoolean("per-player-loot", false);
            } else {
                this.perPlayerLoot = false;
            }

            // Load loot tables
            File tablesDir = new File(plugin.getDataFolder(), "tables");
            if (!tablesDir.exists()) {
                tablesDir.mkdirs();
                plugin.getLogger().info("Created tables directory");
            }

            File[] files = tablesDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    loadLootTable(file);
                }
            }

            plugin.getLogger().info("Loaded " + lootTables.size() + " loot tables");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading configuration: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void loadLootTable(File file) {
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            
            String tableName = config.getString("name", file.getName().replace(".yml", ""));
            boolean firstOpenOnly = config.getBoolean("first-open-only", true);
            long respawnCooldown = config.getLong("respawn-cooldown-ms", 0);
            double respawnVariance = config.getDouble("respawn-variance", 10.0);

            List<LootPool> pools = new ArrayList<>();
            ConfigurationSection poolsSection = config.getConfigurationSection("pools");

            if (poolsSection != null) {
                for (String poolKey : poolsSection.getKeys(false)) {
                    LootPool pool = loadPool(poolsSection.getConfigurationSection(poolKey));
                    if (pool != null) {
                        pools.add(pool);
                    }
                }
            }

            LootTable table = new LootTable(tableName, pools, firstOpenOnly, respawnCooldown, respawnVariance);
            lootTables.put(tableName, table);
            plugin.getLogger().info("Loaded loot table: " + tableName + " (" + pools.size() + " pools)");

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load table from " + file.getName() + ": " + e.getMessage());
        }
    }

    private LootPool loadPool(ConfigurationSection section) {
        try {
            String poolName = section.getName();
            int rolls = section.getInt("rolls", 1);
            double rollBonus = section.getDouble("roll-bonus", 0.0);

            List<LootEntry> entries = new ArrayList<>();
            ConfigurationSection entriesSection = section.getConfigurationSection("entries");

            if (entriesSection != null) {
                for (String entryKey : entriesSection.getKeys(false)) {
                    LootEntry entry = loadEntry(entriesSection.getConfigurationSection(entryKey));
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }

            return new LootPool(poolName, entries, rolls, rollBonus);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load pool: " + e.getMessage());
            return null;
        }
    }

    private LootEntry loadEntry(ConfigurationSection section) {
        try {
            String materialStr = section.getString("material");
            if (materialStr == null) {
                return null;
            }

            Material material = Material.matchMaterial(materialStr);
            if (material == null) {
                plugin.getLogger().warning("Invalid material: " + materialStr);
                return null;
            }

            int minAmount = section.getInt("min-amount", 1);
            int maxAmount = section.getInt("max-amount", 1);
            double weight = section.getDouble("weight", 1.0);
            String displayName = section.getString("display-name", null);
            List<String> lore = section.getStringList("lore");
            boolean overwrite = section.getBoolean("overwrite", false);

            List<LootCondition> conditions = new ArrayList<>();
            ConfigurationSection conditionsSection = section.getConfigurationSection("conditions");
            if (conditionsSection != null) {
                for (String condKey : conditionsSection.getKeys(false)) {
                    LootCondition condition = loadCondition(condKey, conditionsSection.getString(condKey));
                    if (condition != null) {
                        conditions.add(condition);
                    }
                }
            }

            return new LootEntry(material, minAmount, maxAmount, weight, displayName, lore, conditions, overwrite);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load entry: " + e.getMessage());
            return null;
        }
    }

    private LootCondition loadCondition(String type, String value) {
        try {
            return switch (type) {
                case "biome" -> LootCondition.Conditions.biome(value);
                case "world" -> LootCondition.Conditions.world(value);
                case "night-only" -> LootCondition.Conditions.nightOnly();
                case "day-only" -> LootCondition.Conditions.dayOnly();
                default -> LootCondition.Conditions.alwaysTrue();
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load condition: " + e.getMessage());
            return LootCondition.Conditions.alwaysTrue();
        }
    }

    public LootTable getLootTable(String name) {
        return lootTables.get(name);
    }

    public Collection<LootTable> getAllLootTables() {
        return lootTables.values();
    }

    public void reloadTables() {
        lootTables.clear();
        loadConfig();
        plugin.getLogger().info("Reloaded loot tables");
    }

    public boolean isPerPlayerLootEnabled() {
        return perPlayerLoot;
    }
}
