package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.*;
import dev.waystone.vallhaloot.util.RateLimiter;
import dev.waystone.vallhaloot.util.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.Vector;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for container open events and triggers loot generation.
 * 
 * THREAD SAFETY:
 * - All loot computation happens async
 * - Inventory application happens on main thread
 * - Container key deduplication prevents double-generation
 * - Uses ConcurrentHashMap for in-flight requests
 */
public class ContainerOpenListener implements Listener {
    private final ValhallaLootPlugin plugin;
    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlightLootGeneration;
    private final RateLimiter debugLimiter = new RateLimiter(500); // Max 1 debug msg per 500ms
    // BUGFIX #1: Structure detection caching to avoid repeated async calls
    private final ConcurrentHashMap<String, String> structureCache = new ConcurrentHashMap<>();

    // Container types that should have loot
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

    public ContainerOpenListener(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
        this.inFlightLootGeneration = new ConcurrentHashMap<>();
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof BlockInventoryHolder)) {
            return;
        }

        BlockInventoryHolder holder = (BlockInventoryHolder) inventory.getHolder();
        Block block = holder.getBlock();

        if (!LOOT_CONTAINERS.contains(block.getType())) {
            return;
        }

        // Require converted tag to act; skip unconverted containers
        BlockState state = block.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            Integer converted = pdc.get(plugin.getConvertedKey(), PersistentDataType.INTEGER);
            if (converted == null || converted != 1) {
                return;
            }
            // Skip player-placed containers: identified by PDC tag set on placement
            Byte placed = pdc.get(plugin.getPlayerPlacedKey(), PersistentDataType.BYTE);
            if (placed != null && placed == (byte)1) {
                return;
            }
        } else {
            return;
        }

        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();

        // Snapshot all context data synchronously before going async
        LootContext context = snapshotContext(player, block);
        String containerKey = context.getContainerKey();

        debugLimiter.execute(() -> {
            plugin.getLogger().info("[CONTAINER OPEN] " + player.getName() + " opened " + 
                block.getType() + " at " + block.getLocation() + " (key: " + containerKey + ")");
        });

        // Check if we should generate loot
        String tableName = determineTableName(block);
        if (tableName == null) {
            return;
        }

        LootTable table = plugin.getConfigManager().getLootTable(tableName);
        if (table == null) {
            return;
        }

        // Check permissions
        if (!player.hasPermission("valloot.table." + tableName)) {
            return;
        }
        
        // CRITICAL FIX: Check if this player already has loot generated for this container
        boolean perPlayerMode = plugin.getConfigManager().isPerPlayerLootEnabled();
        String existingLoot = perPlayerMode ? plugin.getStorageManager().getPlayerLoot(containerKey, player.getUniqueId()) : null;
        
        if (existingLoot != null) {
            // Player already has loot for this container - restore it immediately
            debugLimiter.execute(() -> {
                plugin.getLogger().info("[LOOT RESTORE] Restoring existing loot for " + player.getName() + 
                    " at " + containerKey);
            });
            
            try {
                // Verify holder still exists before accessing inventory
                if (block.getState() instanceof BlockInventoryHolder validHolder) {
                    org.bukkit.inventory.ItemStack[] items = InventorySerializer.fromBase64(existingLoot);
                    Inventory realInventory = validHolder.getInventory();
                    realInventory.clear();
                    for (int i = 0; i < items.length && i < realInventory.getSize(); i++) {
                        if (items[i] != null) {
                            realInventory.setItem(i, items[i]);
                        }
                    }
                } else {
                    plugin.getLogger().warning("Container no longer valid when restoring loot at " + containerKey);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore player loot: " + e.getMessage());
                // Fall through to generation if restore fails
            }
            return;
        }

        // Check first-open gate
        if (table.isFirstOpenOnly()) {
            boolean opened = perPlayerMode
                ? plugin.getStorageManager().isOpenedByPlayer(containerKey, player.getUniqueId())
                : plugin.getStorageManager().isOpened(containerKey);
            if (opened && !player.hasPermission("valloot.bypass")) {
                return;
            }
        }

        // BUGFIX #2: Handle per-player simultaneous opens properly
        CompletableFuture<?> existing = inFlightLootGeneration.get(containerKey + ":" + player.getUniqueId());
        if (existing != null && !existing.isDone()) {
            // Loot generation is already in progress for this player+container
            return;
        }

        // Start async loot generation for this player
        generateLootAsync(containerKey, table, context, block);
    }

    /**
     * Generate loot asynchronously and apply it to the container.
     * This is the core pattern: compute async, apply sync.
     * Uses per-player tracking to ensure each player gets their own unique loot.
     */
    private void generateLootAsync(String containerKey, LootTable table, LootContext context, Block block) {
        // Use per-player key for tracking in-flight generation
        String playerContainerKey = containerKey + ":" + context.getPlayerUUID();
        CompletableFuture<LootRollResult> future = new CompletableFuture<>();
        inFlightLootGeneration.put(playerContainerKey, future);

        plugin.getSchedulerHelper().runAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                
                // Roll loot (pure logic, safe for async)
                LootRollResult result = LootEngine.roll(table, context);
                
                // Apply ValhallaMMO modifiers if available
                Player player = Bukkit.getPlayer(context.getPlayerUUID());
                if (player != null) {
                    LootModifier modifier = plugin.getValhallaHook().getModifier(player, context);
                    LootEngine.applyModifier(result, modifier);
                }

                long elapsed = System.currentTimeMillis() - start;
                debugLimiter.execute(() -> {
                    plugin.getLogger().info("[LOOT ROLL] Generated " + result.getItems().size() + 
                        " items in " + elapsed + "ms for " + table.getName());
                });

                future.complete(result);
            } catch (Exception e) {
                plugin.getLogger().warning("Error generating loot: " + e.getMessage());
                e.printStackTrace();
                future.completeExceptionally(e);
            }
        });

        // Apply loot to container on main thread when ready
        // Don't block waiting for the future
        future.thenAcceptAsync(result -> {
            applyLootToContainer(block, result, context.getContainerKey());
        }, r -> plugin.getSchedulerHelper().runSync(r));
    }

    /**
     * Apply loot items to the container inventory.
     * MUST be called on main thread (Bukkit API access).
     * 
     * CRITICAL: This completely transforms the container's inventory contents
     * for each player independently by storing and restoring per-player loot.
     * This is the proper "client-side loot" approach like JustLootIt.
     */
    private void applyLootToContainer(Block block, LootRollResult result, String containerKey) {
        // BUGFIX #5: Check if chunk is still loaded before accessing block state
        if (!block.getChunk().isLoaded()) {
            plugin.debug(dev.waystone.vallhaloot.util.DebugLevel.HIGH,
                "Chunk unloaded before loot callback; skipping application at %s", block.getLocation());
            String playerContainerKey = containerKey + ":" + result.getContext().getPlayerUUID();
            inFlightLootGeneration.remove(playerContainerKey);
            return;
        }
        
        if (block.getState() instanceof BlockInventoryHolder holder) {
            Inventory realInventory = holder.getInventory();
            Player viewer = Bukkit.getPlayer(result.getContext().getPlayerUUID());
            
            if (viewer != null) {
                // CRITICAL: Clear and populate the actual container with this player's loot
                realInventory.clear();
                
                int slot = 0;
                for (org.bukkit.inventory.ItemStack item : result.getItems()) {
                    if (slot >= realInventory.getSize()) break;
                    realInventory.setItem(slot++, item);
                }
                
                // CRITICAL: Save this player's loot so they see it consistently on future opens
                boolean perPlayerMode = plugin.getConfigManager().isPerPlayerLootEnabled();
                if (perPlayerMode) {
                    try {
                        org.bukkit.inventory.ItemStack[] contents = realInventory.getContents();
                        String serialized = InventorySerializer.toBase64(contents);
                        plugin.getStorageManager().savePlayerLoot(containerKey, viewer.getUniqueId(), serialized);
                        
                        debugLimiter.execute(() -> {
                            plugin.getLogger().info("[LOOT SAVED] Stored per-player loot for " + viewer.getName() + 
                                " at " + containerKey + " (" + result.getItems().size() + " items)");
                        });
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to serialize and save player loot: " + e.getMessage());
                    }
                }
                
                debugLimiter.execute(() -> {
                    plugin.getLogger().info("[LOOT APPLIED] Container at " + block.getLocation() + 
                        " transformed with " + result.getItems().size() + " items for " + viewer.getName());
                });
                
                // Mark as opened (per-player tracking)
                Vector blockVec = block.getLocation().toVector();
                plugin.getStorageManager().markAsOpenedByPlayer(containerKey,
                    block.getWorld().getUID().toString(),
                    blockVec.getBlockX(), blockVec.getBlockY(), blockVec.getBlockZ(),
                    result.getContext().getPlayerUUID());
            }
        }

        // Clean up in-flight tracking (use per-player key)
        String playerContainerKey = containerKey + ":" + result.getContext().getPlayerUUID();
        inFlightLootGeneration.remove(playerContainerKey);
    }

    /**
     * Determine which loot table should be used for this block.
     * Configurable by block type, biome, world, etc.
     * 
     * CRITICAL FIX: Run slow detection synchronously on cache miss to ensure
     * correct loot table on first open (not just a race-condition fallback to "common").
     * This only happens once per unique container location.
     */
    private String determineTableName(Block block) {
        // Try fast structure detection first (minimal main thread impact)
        String structureTable = detectStructureTableFast(block);
        if (structureTable != null) {
            return structureTable;
        }
        
        // Check cache for previous slow detection result
        String blockKey = block.getX() + ":" + block.getY() + ":" + block.getZ();
        if (structureCache.containsKey(blockKey)) {
            return structureCache.get(blockKey);
        }
        
        // CRITICAL: Run slow detection synchronously on first check
        // This ensures correct loot on first open, not defaulting to "common"
        // Performance: Only happens once per container location, results are cached
        String slowResult = detectStructureTableSlow(block);
        if (slowResult != null) {
            structureCache.put(blockKey, slowResult);
            return slowResult;
        }
        
        // Fall back to block type mapping only if no structure detected
        return switch (block.getType()) {
            case BARREL -> "common";
            case CHEST -> "common";
            case TRAPPED_CHEST -> "rare";
            default -> null;
        };
    }

    /**
     * Fast structure detection (main thread safe, minimal performance impact).
     * Only checks immediate surroundings and common patterns.
     * 
     * CRITICAL: Avoid dimension-wide assumptions (not all Nether chests are fortresses!)
     */
    private String detectStructureTableFast(Block block) {
        String biome = block.getBiome().toString().toLowerCase();
        
        // Only use biome detection for structures that are biome-exclusive
        // Removed dimension-wide checks - they're too broad and inaccurate
        // Examples of removed bad logic:
        // - All Nether chests != Nether Fortress (could be Bastion, Ruined Portal, etc.)
        // - All End chests != End City (could be naturally generated, player-placed, etc.)
        
        // Biome-specific structures (more reliable)
        if (biome.contains("desert") && !biome.contains("cold")) {
            // Desert Pyramids only spawn in warm desert biomes
            return "desert_pyramid";
        }
        if (biome.contains("jungle")) {
            // Jungle Temples only in jungle biomes
            return "jungle_temple";
        }
        
        // Don't assume structure from dimension alone - let slow detection handle it
        return null;
    }

    /**
     * Slow/detailed structure detection (async only).
     * Uses extensive block scanning within a 15-block radius.
     * MUST only be called from async context.
     */
    private String detectStructureTableSlow(Block block) {
        // Variables kept for future use
        @SuppressWarnings("unused")
        String biome = block.getBiome().toString().toLowerCase();
        @SuppressWarnings("unused")
        int x = block.getX();
        @SuppressWarnings("unused")
        int y = block.getY();
        @SuppressWarnings("unused")
        int z = block.getZ();
        
        // Check for Stronghold (always underground, specific blocks around it)
        if (isInStronghold(block)) {
            return "stronghold";
        }
        
        // Check for Village (lots of hay/wood/doors/beds in area)
        if (isInVillage(block)) {
            return "village";
        }
        
        // Check for Mansion (large dark oak structures)
        if (isInMansion(block)) {
            return "mansion";
        }
        
        // Check for Nether Fortress (nether brick, red carpet patterns)
        if (isInNetherFortress(block)) {
            return "nether_fortress";
        }
        
        // Check for Bastion Remnant (blackstone, gold blocks)
        if (isInBastionRemnant(block)) {
            return "bastion_remnant";
        }
        
        // Check for Ancient City (sculk, deepslate)
        if (isInAncientCity(block)) {
            return "ancient_city";
        }
        
        // Check for Pillager Outpost (dark oak, grey concrete)
        if (isInPillargerOutpost(block)) {
            return "pillager_outpost";
        }
        
        // Check for Desert Pyramid (sandstone, terracotta)
        if (isInDesertPyramid(block)) {
            return "desert_pyramid";
        }
        
        // Check for Jungle Temple (mossy stone, vines)
        if (isInJungleTemple(block)) {
            return "jungle_temple";
        }
        
        // Check for Ocean Ruins (sandstone, gravel underwater)
        if (isInOceanRuins(block)) {
            return "ocean_ruins";
        }
        
        // Check for Shipwreck (oak/spruce wood, specific patterns)
        if (isInShipwreck(block)) {
            return "shipwreck";
        }
        
        // Check for End City (purpur blocks, end rods)
        if (isInEndCity(block)) {
            return "end_city";
        }
        
        return null;
    }

    private boolean isInStronghold(Block block) {
        // Strongholds have stone brick, dark oak, silverfish spawners
        // Usually deep underground (y < 40)
        int checkRadius = 15;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("STONE_BRICK") || type.contains("DARK_OAK")) {
                    return block.getY() < 50; // Strongholds are underground
                }
            }
        }
        return false;
    }

    private boolean isInVillage(Block block) {
        // Villages have lots of wooden structures, hay, doors, composter, beds
        int checkRadius = 20;
        int structureBlocks = 0;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("LOG") || type.contains("DOOR") || type.contains("BED") || type.contains("HAY")) {
                    structureBlocks++;
                }
            }
        }
        return structureBlocks > 5;
    }

    private boolean isInMansion(Block block) {
        // Mansions are made of dark oak wood
        int checkRadius = 15;
        int darkOakCount = 0;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                if (check.getType().toString().contains("DARK_OAK")) {
                    darkOakCount++;
                }
            }
        }
        return darkOakCount > 8;
    }

    private boolean isInNetherFortress(Block block) {
        // Nether fortresses are made of nether brick, red carpet
        if (!block.getWorld().getName().toLowerCase().contains("nether")) {
            return false;
        }
        int checkRadius = 15;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("NETHER_BRICK")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInBastionRemnant(Block block) {
        // Bastion remnants are made of blackstone, gold blocks
        if (!block.getWorld().getName().toLowerCase().contains("nether")) {
            return false;
        }
        int checkRadius = 15;
        int blackstoneCount = 0;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("BLACKSTONE") || type.contains("GOLD_BLOCK")) {
                    blackstoneCount++;
                }
            }
        }
        return blackstoneCount > 3;
    }

    private boolean isInAncientCity(Block block) {
        // Ancient cities have sculk blocks, deep below Y=0
        int checkRadius = 20;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                if (check.getType().toString().contains("SCULK")) {
                    return block.getY() < -10;
                }
            }
        }
        return false;
    }

    private boolean isInPillargerOutpost(Block block) {
        // Pillager outposts have dark oak wood, grey concrete, flags
        int checkRadius = 15;
        int outpostBlocks = 0;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("DARK_OAK") || type.contains("GREY_CONCRETE")) {
                    outpostBlocks++;
                }
            }
        }
        return outpostBlocks > 5;
    }

    private boolean isInDesertPyramid(Block block) {
        // Desert pyramids are sandstone, terracotta, in desert biome
        String biome = block.getBiome().toString().toLowerCase();
        if (!biome.contains("desert")) {
            return false;
        }
        int checkRadius = 15;
        int sandstoneCount = 0;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("SANDSTONE") || type.contains("TERRACOTTA")) {
                    sandstoneCount++;
                }
            }
        }
        return sandstoneCount > 5;
    }

    private boolean isInJungleTemple(Block block) {
        // Jungle temples are mossy stone, vines, in jungle
        String biome = block.getBiome().toString().toLowerCase();
        if (!biome.contains("jungle")) {
            return false;
        }
        int checkRadius = 15;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                String type = check.getType().toString();
                if (type.contains("MOSSY_STONE") || type.equals("VINE")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInOceanRuins(Block block) {
        // Ocean ruins are underwater with sandstone, gravel
        if (block.getType().toString().contains("WATER")) {
            int checkRadius = 15;
            for (int dx = -checkRadius; dx <= checkRadius; dx++) {
                for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                    Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                    String type = check.getType().toString();
                    if (type.contains("SANDSTONE") || type.equals("GRAVEL")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInShipwreck(Block block) {
        // Shipwrecks are underwater with spruce/oak wood
        if (block.getType().toString().contains("WATER")) {
            int checkRadius = 10;
            for (int dx = -checkRadius; dx <= checkRadius; dx++) {
                for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                    Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                    String type = check.getType().toString();
                    if (type.contains("OAK") || type.contains("SPRUCE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInEndCity(Block block) {
        // End cities have purpur blocks, end rods
        if (!block.getWorld().getName().toLowerCase().contains("end")) {
            return false;
        }
        int checkRadius = 15;
        for (int dx = -checkRadius; dx <= checkRadius; dx++) {
            for (int dz = -checkRadius; dz <= checkRadius; dz++) {
                Block check = block.getWorld().getBlockAt(block.getX() + dx, block.getY(), block.getZ() + dz);
                if (check.getType().toString().contains("PURPUR")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Snapshot all context data synchronously.
     * This must happen on the main thread to safely access Bukkit API.
     */
    private LootContext snapshotContext(Player player, Block block) {
        Vector location = block.getLocation().toVector();
        String worldName = block.getWorld().getName();
        UUID worldUUID = block.getWorld().getUID();
        String biome = block.getBiome().toString();
        long worldTime = block.getWorld().getTime();
        int moonPhase = 0; // Calculate from world time
        boolean isNight = worldTime >= 13000 || worldTime <= 23000;

        Map<String, Object> metadata = plugin.getValhallaHook().enrichContext(player, null);
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        return new LootContext(
            player.getUniqueId(),
            player.getName(),
            location,
            worldName,
            worldUUID,
            block.getType().toString(),
            biome,
            worldTime,
            moonPhase,
            isNight,
            0, // PlayerLevel would come from ValhallaMMO
            metadata
        );
    }
}
