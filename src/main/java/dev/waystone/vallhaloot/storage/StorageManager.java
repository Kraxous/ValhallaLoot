package dev.waystone.vallhaloot.storage;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import org.bukkit.util.Vector;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import java.io.File;
import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent storage of "first-open" markers and respawn cooldowns.
 * Uses SQLite for reliability across server restarts.
 * All methods are thread-safe.
 */
public class StorageManager {
    private final ValhallaLootPlugin plugin;
    private final Connection dbConnection;
    private final ConcurrentHashMap<String, Long> openMarkers; // Cache for performance (global)
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, Long>> playerOpenMarkers; // per-player cache

    public StorageManager(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
        this.openMarkers = new ConcurrentHashMap<>();
        this.playerOpenMarkers = new ConcurrentHashMap<>();
        this.dbConnection = initializeDatabase();
        loadAllMarkers();
    }

    private Connection initializeDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "valloot.db");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Create tables if they don't exist
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS first_opens (" +
                        "container_key TEXT PRIMARY KEY," +
                        "world_uuid TEXT NOT NULL," +
                        "x INTEGER NOT NULL," +
                        "y INTEGER NOT NULL," +
                        "z INTEGER NOT NULL," +
                        "opened_at BIGINT NOT NULL," +
                        "player_uuid TEXT NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS respawn_cooldowns (" +
                        "container_key TEXT PRIMARY KEY," +
                        "table_name TEXT NOT NULL," +
                        "next_respawn_at BIGINT NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS first_opens_by_player (" +
                    "container_key TEXT NOT NULL," +
                    "player_uuid TEXT NOT NULL," +
                    "world_uuid TEXT NOT NULL," +
                    "x INTEGER NOT NULL," +
                    "y INTEGER NOT NULL," +
                    "z INTEGER NOT NULL," +
                    "opened_at BIGINT NOT NULL," +
                    "PRIMARY KEY(container_key, player_uuid))");

                stmt.execute("CREATE TABLE IF NOT EXISTS original_inventories (" +
                        "container_key TEXT PRIMARY KEY," +
                        "data TEXT NOT NULL)");
            }

            plugin.getLogger().info("Database initialized successfully");
            return conn;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void loadAllMarkers() {
        if (dbConnection == null) {
            return;
        }

        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT container_key, opened_at FROM first_opens")) {
            while (rs.next()) {
                openMarkers.put(rs.getString("container_key"), rs.getLong("opened_at"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load first-open markers: " + e.getMessage());
        }

        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT container_key, player_uuid, opened_at FROM first_opens_by_player")) {
            while (rs.next()) {
                String key = rs.getString("container_key");
                UUID player = UUID.fromString(rs.getString("player_uuid"));
                long at = rs.getLong("opened_at");
                playerOpenMarkers.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(player, at);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load per-player first-open markers: " + e.getMessage());
        }
    }

    /**
     * Check if a container has been opened before.
     */
    public boolean isOpened(String containerKey) {
        return openMarkers.containsKey(containerKey);
    }

    public boolean isOpenedByPlayer(String containerKey, UUID playerUUID) {
        ConcurrentHashMap<UUID, Long> map = playerOpenMarkers.get(containerKey);
        return map != null && map.containsKey(playerUUID);
    }

    /**
     * Mark a container as opened.
     */
    public void markAsOpened(String containerKey, String worldUUID, int x, int y, int z, String playerUUID) {
        openMarkers.put(containerKey, System.currentTimeMillis());

        // Async write to database
        plugin.getSchedulerHelper().runAsync(() -> {
            if (dbConnection == null) {
                return;
            }

            try (PreparedStatement pstmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO first_opens (container_key, world_uuid, x, y, z, opened_at, player_uuid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                pstmt.setString(1, containerKey);
                pstmt.setString(2, worldUUID);
                pstmt.setInt(3, x);
                pstmt.setInt(4, y);
                pstmt.setInt(5, z);
                pstmt.setLong(6, System.currentTimeMillis());
                pstmt.setString(7, playerUUID);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to mark container as opened: " + e.getMessage());
            }
        });
    }

    public void markAsOpenedByPlayer(String containerKey, String worldUUID, int x, int y, int z, UUID playerUUID) {
        playerOpenMarkers.computeIfAbsent(containerKey, k -> new ConcurrentHashMap<>())
            .put(playerUUID, System.currentTimeMillis());

        // Async write to database
        plugin.getSchedulerHelper().runAsync(() -> {
            if (dbConnection == null) {
                return;
            }

            try (PreparedStatement pstmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO first_opens_by_player (container_key, player_uuid, world_uuid, x, y, z, opened_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                pstmt.setString(1, containerKey);
                pstmt.setString(2, playerUUID.toString());
                pstmt.setString(3, worldUUID);
                pstmt.setInt(4, x);
                pstmt.setInt(5, y);
                pstmt.setInt(6, z);
                pstmt.setLong(7, System.currentTimeMillis());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to mark per-player container as opened: " + e.getMessage());
            }
        });
    }

    /**
     * Check if a container's respawn cooldown has expired.
     */
    public boolean canRespawn(String containerKey) {
        if (dbConnection == null) {
            return true;
        }
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "SELECT next_respawn_at FROM respawn_cooldowns WHERE container_key = ?")) {
            pstmt.setString(1, containerKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return System.currentTimeMillis() >= rs.getLong("next_respawn_at");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to check respawn cooldown: " + e.getMessage());
        }
        return true; // Default to allowing respawn if DB error
    }

    /**
     * Set the respawn cooldown for a container.
     */
    public void setRespawnCooldown(String containerKey, String tableName, long cooldownMs) {
        long nextRespawn = System.currentTimeMillis() + cooldownMs;

        plugin.getSchedulerHelper().runAsync(() -> {
            if (dbConnection == null) {
                return;
            }

            try (PreparedStatement pstmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO respawn_cooldowns (container_key, table_name, next_respawn_at) " +
                    "VALUES (?, ?, ?)")) {
                pstmt.setString(1, containerKey);
                pstmt.setString(2, tableName);
                pstmt.setLong(3, nextRespawn);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to set respawn cooldown: " + e.getMessage());
            }
        });
    }

    /**
     * Clean up old records (older than 30 days).
     */
    public void cleanup() {
        cleanup(true);
    }

    /**
     * Clean up old records (older than 30 days).
     * @param async Whether to run asynchronously (false for shutdown)
     */
    private void cleanup(boolean async) {
        Runnable cleanupTask = () -> {
            if (dbConnection == null) {
                return;
            }

            try (Statement stmt = dbConnection.createStatement()) {
                long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L);
                stmt.executeUpdate("DELETE FROM first_opens WHERE opened_at < " + thirtyDaysAgo);
                stmt.executeUpdate("DELETE FROM respawn_cooldowns WHERE next_respawn_at < " + System.currentTimeMillis());
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to clean up database: " + e.getMessage());
            }
        };

        if (async) {
            plugin.getSchedulerHelper().runAsync(cleanupTask);
        } else {
            cleanupTask.run();
        }
    }

    public String getOriginalInventory(String containerKey) {
        if (dbConnection == null) return null;
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "SELECT data FROM original_inventories WHERE container_key = ?")) {
            pstmt.setString(1, containerKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("data");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to fetch original inventory: " + e.getMessage());
        }
        return null;
    }

    public void saveOriginalInventory(String containerKey, String data) {
        if (dbConnection == null) return;
        plugin.getSchedulerHelper().runAsync(() -> {
            try (PreparedStatement pstmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO original_inventories (container_key, data) VALUES (?, ?)")) {
                pstmt.setString(1, containerKey);
                pstmt.setString(2, data);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save original inventory: " + e.getMessage());
            }
        });
    }

    public void removeOriginalInventory(String containerKey) {
        if (dbConnection == null) return;
        plugin.getSchedulerHelper().runAsync(() -> {
            try (PreparedStatement pstmt = dbConnection.prepareStatement(
                    "DELETE FROM original_inventories WHERE container_key = ?")) {
                pstmt.setString(1, containerKey);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to remove original inventory: " + e.getMessage());
            }
        });
    }

    /**
     * Check if a world has any converted containers.
     * Used to determine if auto-conversion should be enabled for a world.
     */
    public boolean hasConvertedContainers(String worldName) {
        if (dbConnection == null) {
            return false;
        }
        try (PreparedStatement pstmt = dbConnection.prepareStatement(
                "SELECT 1 FROM original_inventories WHERE container_key LIKE ? LIMIT 1")) {
            pstmt.setString(1, worldName + ":%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to check if world has converted containers: " + e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        cleanup(false); // Final cleanup - run synchronously
        if (dbConnection != null) {
            try {
                dbConnection.close();
                plugin.getLogger().info("Database connection closed");
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing database: " + e.getMessage());
            }
        }
    }
}
