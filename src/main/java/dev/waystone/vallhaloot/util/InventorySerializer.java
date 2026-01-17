package dev.waystone.vallhaloot.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class InventorySerializer {
    private InventorySerializer() {}

    public static String toBase64(ItemStack[] contents) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(contents.length);
            for (ItemStack stack : contents) {
                oos.writeObject(stack);
            }
            oos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize inventory", e);
        }
    }

    public static ItemStack[] fromBase64(String base64) {
        byte[] data = Base64.getDecoder().decode(base64);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            int size = ois.readInt();
            ItemStack[] stacks = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                try {
                    stacks[i] = (ItemStack) ois.readObject();
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException("Failed to deserialize inventory", ex);
                }
            }
            return stacks;
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize inventory", e);
        }
    }
}
