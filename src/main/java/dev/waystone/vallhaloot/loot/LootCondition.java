package dev.waystone.vallhaloot.loot;

/**
 * Condition for loot entry applicability.
 * Examples: time of day, biome, world, permission, skill threshold.
 */
public interface LootCondition {
    /**
     * Test if this condition is satisfied in the given context.
     */
    boolean test(LootContext context);

    /**
     * Factory for common conditions.
     */
    class Conditions {
        public static LootCondition biome(String requiredBiome) {
            return ctx -> ctx.getBiome().equalsIgnoreCase(requiredBiome);
        }

        public static LootCondition world(String requiredWorld) {
            return ctx -> ctx.getWorldName().equalsIgnoreCase(requiredWorld);
        }

        public static LootCondition nightOnly() {
            return LootContext::isNight;
        }

        public static LootCondition dayOnly() {
            return ctx -> !ctx.isNight();
        }

        public static LootCondition skillThreshold(String skillName, int minLevel) {
            return ctx -> {
                Object level = ctx.getMetadata("skill_" + skillName);
                if (level instanceof Number) {
                    return ((Number) level).intValue() >= minLevel;
                }
                return false;
            };
        }

        public static LootCondition permission(String permission) {
            return ctx -> {
                @SuppressWarnings("unused")
                Object player = ctx.getMetadata("player_obj");
                // This would need player object from context
                // For now, stored as metadata by integration layer
                return (Boolean) ctx.getMetadata("perm_" + permission) == Boolean.TRUE;
            };
        }

        public static LootCondition alwaysTrue() {
            return ctx -> true;
        }
    }
}
