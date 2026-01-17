package dev.waystone.vallhaloot.loot;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Snapshot of context data for a loot roll.
 * Safe to use off-thread as it contains only primitive/immutable data.
 */
public class LootContext {
    private final UUID playerUUID;
    private final String playerName;
    private final Vector blockLocation;
    private final String worldName;
    private final UUID worldUUID;
    private final String containerType;
    private final String biome;
    private final long worldTime;
    private final int moonPhase;
    private final boolean isNight;
    private final int playerLevel; // ValhallaMMO level placeholder
    
    private final Map<String, Object> metadata; // For ValhallaMMO data

    public LootContext(UUID playerUUID, String playerName, Vector blockLocation, 
                      String worldName, UUID worldUUID, String containerType,
                      String biome, long worldTime, int moonPhase, boolean isNight,
                      int playerLevel, Map<String, Object> metadata) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.blockLocation = blockLocation;
        this.worldName = worldName;
        this.worldUUID = worldUUID;
        this.containerType = containerType;
        this.biome = biome;
        this.worldTime = worldTime;
        this.moonPhase = moonPhase;
        this.isNight = isNight;
        this.playerLevel = playerLevel;
        this.metadata = new HashMap<>(metadata);
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public String getPlayerName() { return playerName; }
    public Vector getBlockLocation() { return blockLocation; }
    public String getWorldName() { return worldName; }
    public UUID getWorldUUID() { return worldUUID; }
    public String getContainerType() { return containerType; }
    public String getBiome() { return biome; }
    public long getWorldTime() { return worldTime; }
    public int getMoonPhase() { return moonPhase; }
    public boolean isNight() { return isNight; }
    public int getPlayerLevel() { return playerLevel; }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /**
     * Get metadata value for ValhallaMMO integration.
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Unique key for this container location.
     */
    public String getContainerKey() {
        return String.format("%s_%d_%d_%d", 
            worldUUID.toString().substring(0, 8),
            (int) blockLocation.getX(),
            (int) blockLocation.getY(),
            (int) blockLocation.getZ());
    }

    @Override
    public String toString() {
        return "LootContext{" +
                "player=" + playerName + " (" + playerUUID + ")," +
                "location=" + blockLocation +
                ", world=" + worldName +
                ", container=" + containerType +
                ", biome=" + biome +
                '}';
    }
}
