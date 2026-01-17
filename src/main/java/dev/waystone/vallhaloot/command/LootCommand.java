package dev.waystone.vallhaloot.command;

import dev.waystone.vallhaloot.ValhallaLootPlugin;
import dev.waystone.vallhaloot.loot.*;
import dev.waystone.vallhaloot.storage.StorageManager;
import dev.waystone.vallhaloot.util.DebugLevel;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command handler for /valloot commands.
 */
public class LootCommand implements CommandExecutor {
    private final ValhallaLootPlugin plugin;
    private final StorageManager storage;
    private final ConvertCommand convertCommand;
    private final RestoreCommand restoreCommand;

    public LootCommand(ValhallaLootPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.convertCommand = new ConvertCommand(plugin, storage);
        this.restoreCommand = new RestoreCommand(plugin, storage);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("valloot.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();
        return switch (subcommand) {
            case "reload" -> handleReload(sender);
            case "test" -> handleTest(sender, args);
            case "status" -> convertCommand.onStatus(sender, copyRemainingArgs(args));
            case "bg-status" -> handleBackgroundStatus(sender);
            case "convert" -> convertCommand.onCommand(sender, command, label, copyRemainingArgs(args));
            case "restore" -> restoreCommand.onCommand(sender, command, label, copyRemainingArgs(args));
            default -> sendUsage(sender);
        };
    }

    private boolean handleReload(CommandSender sender) {
        sender.sendMessage("§eReloading ValhallaLoot configuration...");
        plugin.reloadConfig();
        plugin.getConfigManager().reloadTables();
        
        // Reload debug level from config
        String debugLevelStr = plugin.getConfig().getString("debug-level", "NORMAL");
        plugin.setDebugLevel(DebugLevel.fromString(debugLevelStr));
        
        sender.sendMessage("§aConfiguration reloaded successfully!");
        sender.sendMessage("§7Debug level: §e" + plugin.getDebugLevel().name());
        if (plugin.getConfig().getBoolean("auto-convert-on-chunk-load", false)) {
            sender.sendMessage("§7Background chunk-load conversion: §aENABLED");
        } else {
            sender.sendMessage("§7Background chunk-load conversion: §cDISABLED");
        }
        return true;
    }

    private boolean handleBackgroundStatus(CommandSender sender) {
        if (!sender.hasPermission("valloot.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        sender.sendMessage("§e========================================");
        sender.sendMessage("§e  BACKGROUND CONVERSION STATUS");
        sender.sendMessage("§e========================================");
        
        boolean enabled = plugin.getConfig().getBoolean("auto-convert-on-chunk-load", false);
        int converted = plugin.getChunkLoadListener().getBackgroundConversionCount();
        
        sender.sendMessage("§eStatus: " + (enabled ? "§aENABLED" : "§cDISABLED"));
        sender.sendMessage("§eContainers converted: §b" + converted);
        sender.sendMessage("§e");
        
        if (enabled) {
            sender.sendMessage("§7Containers are being automatically converted");
            sender.sendMessage("§7as new chunks are loaded by players.");
        } else {
            sender.sendMessage("§7Background conversion is disabled.");
            sender.sendMessage("§7Enable in config: auto-convert-on-chunk-load: true");
        }
        
        return true;
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /valloot test <table> [player] [--give]");
            return true;
        }

        String tableName = args[1];
        LootTable table = plugin.getConfigManager().getLootTable(tableName);

        if (table == null) {
            sender.sendMessage("§cLoot table '" + tableName + "' not found.");
            return true;
        }

        Player player = null;
        if (args.length >= 3) {
            player = Bukkit.getPlayer(args[2]);
            if (player == null) {
                sender.sendMessage("§cPlayer '" + args[2] + "' not found.");
                return true;
            }
        } else if (sender instanceof Player) {
            player = (Player) sender;
        } else {
            sender.sendMessage("§cNo player specified and you are not a player.");
            return true;
        }

        boolean giveItems = args.length >= 4 && args[3].equals("--give");

        // Create a dummy context for testing
        LootContext context = new LootContext(
            player.getUniqueId(),
            player.getName(),
            player.getLocation().toVector(),
            player.getWorld().getName(),
            player.getWorld().getUID(),
            "test",
            player.getLocation().getBlock().getBiome().toString(),
            player.getWorld().getTime(),
            0,
            player.getWorld().getTime() >= 13000,
            0,
            java.util.Collections.emptyMap()
        );

        // Roll loot
        LootRollResult result = LootEngine.roll(table, context);

        sender.sendMessage("§e=== Loot Roll Result ===");
        sender.sendMessage("§6Table: §f" + tableName);
        sender.sendMessage("§6Player: §f" + player.getName());
        sender.sendMessage("§6Items rolled: §f" + result.getItems().size());
        sender.sendMessage("§6Generation time: §f" + result.getRollTimeMs() + "ms");

        if (result.getItems().isEmpty()) {
            sender.sendMessage("§c(No items rolled)");
        } else {
            sender.sendMessage("§6Items:");
            for (org.bukkit.inventory.ItemStack item : result.getItems()) {
                sender.sendMessage("  §f- " + item.getType() + " x" + item.getAmount());
            }
        }

        if (giveItems && player != null) {
            for (org.bukkit.inventory.ItemStack item : result.getItems()) {
                player.getInventory().addItem(item);
            }
            sender.sendMessage("§aItems given to " + player.getName());
        }

        return true;
    }

    private boolean sendUsage(CommandSender sender) {
        sender.sendMessage("§e=== ValhallaLoot Commands ===");
        sender.sendMessage("§6/valloot reload §f- Reload configuration");
        sender.sendMessage("§6/valloot status [world] §f- Check manual conversion status");
        sender.sendMessage("§6/valloot bg-status §f- Check background conversion status");
        sender.sendMessage("§6/valloot test <table> [player] [--give] §f- Test a loot table");
        sender.sendMessage("§6/valloot convert <world|all> [--load-all-chunks] §f- Convert containers (manual)");
        sender.sendMessage("§6/valloot restore <world|all> confirm §f- Restore original inventories");
        return true;
    }

    private String[] copyRemainingArgs(String[] args) {
        String[] result = new String[args.length - 1];
        System.arraycopy(args, 1, result, 0, args.length - 1);
        return result;
    }
}
