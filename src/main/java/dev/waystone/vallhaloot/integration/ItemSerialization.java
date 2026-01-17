package dev.waystone.vallhaloot.integration;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Utility to deserialize base64 serialized ItemStack strings from ValhallaTrinkets defaults.
 */
public class ItemSerialization {
    public static ItemStack deserializeItemStack(String base64){
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))){
                Object obj = in.readObject();
                if (obj instanceof ItemStack) return (ItemStack) obj;
            }
        } catch (IOException | ClassNotFoundException ignored) {
        }
        return null;
    }
}
