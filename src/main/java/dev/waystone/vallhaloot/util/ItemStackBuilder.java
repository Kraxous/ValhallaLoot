package dev.waystone.vallhaloot.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for ItemStacks with common customizations.
 */
public class ItemStackBuilder {
    private final ItemStack itemStack;
    private ItemMeta meta;

    public ItemStackBuilder(Material material) {
        this(material, 1);
    }

    public ItemStackBuilder(Material material, int amount) {
        this.itemStack = new ItemStack(material, amount);
        this.meta = itemStack.getItemMeta();
    }

    @SuppressWarnings("deprecation")
    public ItemStackBuilder withName(String displayName) {
        if (meta != null) {
            meta.setDisplayName(displayName);
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemStackBuilder withLore(String... loreLines) {
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(line);
            }
            meta.setLore(lore);
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemStackBuilder withLore(List<String> lore) {
        if (meta != null) {
            meta.setLore(lore);
        }
        return this;
    }

    public ItemStackBuilder withCustomModelData(int modelData) {
        if (meta != null) {
            meta.setCustomModelData(modelData);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}
