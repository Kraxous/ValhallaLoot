package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.*;
import dev.waystone.vallhaloot.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
            Byte converted = pdc.get(plugin.getConvertedKey(), PersistentDataType.BYTE);
            if (converted == null || converted != (byte)1) {
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

        // Check first-open gate
        if (table.isFirstOpenOnly()) {
            boolean perPlayer = plugin.getConfigManager().isPerPlayerLootEnabled();
            boolean opened = perPlayer
                ? plugin.getStorageManager().isOpenedByPlayer(containerKey, player.getUniqueId())
                : plugin.getStorageManager().isOpened(containerKey);
            if (opened && !player.hasPermission("valloot.bypass")) {
                return;
            }
        }

        // Coalesce duplicate requests for same container
        CompletableFuture<?> existing = inFlightLootGeneration.get(containerKey);
        if (existing != null && !existing.isDone()) {
            // Loot generation is already in progress for this container
            // Just wait for it to complete (we don't block; we rely on inventory listener)
            return;
        }

        // Start async loot generation
        generateLootAsync(containerKey, table, context, block);
    }

    /**
     * Generate loot asynchronously and apply it to the container.
     * This is the core pattern: compute async, apply sync.
     */
    private void generateLootAsync(String containerKey, LootTable table, LootContext context, Block block) {
        CompletableFuture<LootRollResult> future = new CompletableFuture<>();
        inFlightLootGeneration.put(containerKey, future);

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
     */
    private void applyLootToContainer(Block block, LootRollResult result, String containerKey) {
        if (block.getState() instanceof BlockInventoryHolder) {
            BlockInventoryHolder holder = (BlockInventoryHolder) block.getState();
            Inventory realInventory = holder.getInventory();

            // Create a virtual inventory per-player and show rolled items (client-sided)
            Player viewer = Bukkit.getPlayer(result.getContext().getPlayerUUID());
            if (viewer != null) {
                Inventory virtual = Bukkit.createInventory(viewer, realInventory.getSize(), block.getType().name());
                int nextSlot = 0;
                for (org.bukkit.inventory.ItemStack item : result.getItems()) {
                    if (nextSlot >= virtual.getSize()) break;
                    virtual.setItem(nextSlot++, item);
                }
                viewer.openInventory(virtual);
            }

            // Mark as opened (per-player)
            Vector blockVec = block.getLocation().toVector();
            plugin.getStorageManager().markAsOpenedByPlayer(containerKey,
                block.getWorld().getUID().toString(),
                blockVec.getBlockX(), blockVec.getBlockY(), blockVec.getBlockZ(),
                result.getContext().getPlayerUUID());

            debugLimiter.execute(() -> {
                plugin.getLogger().info("[LOOT APPLIED] Opened virtual inventory with " + result.getItems().size() + 
                    " items for player at container " + block.getLocation());
            });
        }

        // Clean up in-flight tracking
        inFlightLootGeneration.remove(containerKey);
    }

    /**
     * Determine which loot table should be used for this block.
     * Configurable by block type, biome, world, etc.
     */
    private String determineTableName(Block block) {
        // TODO: Implement more sophisticated logic based on config
        // For now, simple mapping by block type
        return switch (block.getType()) {
            case BARREL -> "common";
            case CHEST -> "common";
            case TRAPPED_CHEST -> "rare";
            default -> null;
        };
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
        int moonPhase = 0; // TODO: Calculate from world time
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
