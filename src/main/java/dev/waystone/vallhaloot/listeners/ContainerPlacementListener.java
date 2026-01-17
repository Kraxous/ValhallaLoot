package dev.waystone.vallhaloot.listeners;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

/**
 * Tags player-placed containers so loot generation can ignore them.
 */
public class ContainerPlacementListener implements Listener {
    private final ValhallaLootPlugin plugin;

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

    public ContainerPlacementListener(ValhallaLootPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (!LOOT_CONTAINERS.contains(placed.getType())) {
            return;
        }

        BlockState state = placed.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            pdc.set(plugin.getPlayerPlacedKey(), PersistentDataType.BYTE, (byte)1);
            tileState.update(true);
        }
    }
}
