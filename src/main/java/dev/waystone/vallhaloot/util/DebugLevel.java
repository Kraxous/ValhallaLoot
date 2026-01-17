package dev.waystone.vallhaloot.util;

/**
 * Debug levels for ValhallaLoot logging.
 * 
 * LOWEST   - Development/internal debugging (API calls, data flow, performance metrics)
 * LOW      - Detailed system operations (chunk processing, database queries, cache hits)
 * NORMAL   - General operations (loot generation, container conversion, player interactions)
 * HIGH     - Important events (configuration changes, errors, warnings)
 * HIGHEST  - End-user visible (command results, conversion summaries, critical errors)
 */
public enum DebugLevel {
    LOWEST(0, "DEV"),     // Development-only
    LOW(1, "DEBUG"),      // Detailed debugging
    NORMAL(2, "INFO"),    // Standard operations
    HIGH(3, "WARN"),      // Important events
    HIGHEST(4, "USER");   // End-user messages
    
    private final int level;
    private final String prefix;
    
    DebugLevel(int level, String prefix) {
        this.level = level;
        this.prefix = prefix;
    }
    
    public int getLevel() {
        return level;
    }
    
    public String getPrefix() {
        return prefix;
    }
    
    public boolean shouldLog(DebugLevel current) {
        return this.level >= current.level;
    }
    
    public static DebugLevel fromString(String str) {
        try {
            return valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL; // Default to NORMAL if invalid
        }
    }
}
