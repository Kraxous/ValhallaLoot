package dev.waystone.vallhaloot.integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.waystone.vallhaloot.ValhallaLootPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Soft-integration with ValhallaTrinkets: loads default trinkets from its JSON
 * and constructs ItemStacks via base64 ItemStack serialization. If available,
 * tags items as proper trinkets using ValhallaTrinkets APIs via reflection.
 */
public class ValhallaTrinketsBridge {
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>(){}.getType();

    /**
     * Returns a random default trinket ItemStack, or null if unavailable.
     */
    public static ItemStack randomDefaultTrinket(ValhallaLootPlugin plugin){
        try {
            Plugin trinkets = plugin.getServer().getPluginManager().getPlugin("ValhallaTrinkets");
            if (trinkets == null) return null;
            File data = trinkets.getDataFolder();
            File defaults = new File(data, "default_trinkets.json");
            if (!defaults.exists()) return null; // advise running /valhalla setuptrinkets

            List<Map<String, Object>> defs;
            try (FileReader reader = new FileReader(defaults, StandardCharsets.UTF_8)){
                defs = GSON.fromJson(reader, LIST_TYPE);
            }
            if (defs == null || defs.isEmpty()) return null;

            Map<String, Object> def = defs.get(new Random().nextInt(defs.size()));
            String itemBase64 = (String) def.get("item");
            ItemStack stack = ItemSerialization.deserializeItemStack(itemBase64);

            if (stack == null) return null;

            // Attempt to apply trinket properties via reflection if ValhallaTrinkets APIs are present
            try {
                Map<String, Object> modifiers = extractTrinketModifier(def);
                if (modifiers != null) {
                    Integer typeId = (Integer) modifiers.get("trinket");
                    Integer id = (Integer) modifiers.get("id");
                    Boolean unique = (Boolean) modifiers.get("unique");

                    if (typeId != null || id != null || unique != null){
                        tagAsTrinket(stack, typeId, id, unique);
                    }
                }
            } catch (Exception ignored){ }

            return stack;
        } catch (Exception e){
            plugin.getLogger().warning("Failed to get random default trinket: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the TrinketTypeSetModifier block from the default trinket entry
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractTrinketModifier(Map<String, Object> def){
        Object modsObj = def.get("modifiers");
        if (!(modsObj instanceof List)) return null;
        List<Object> mods = (List<Object>) modsObj;
        for (Object m : mods){
            if (!(m instanceof Map)) continue;
            Map<String, Object> mod = (Map<String, Object>) m;
            Object modType = mod.get("MOD_TYPE");
            if (modType instanceof String && ((String) modType).endsWith("TrinketTypeSetModifier")){
                Object data = mod.get("DATA");
                if (data instanceof Map) return (Map<String, Object>) data;
            }
        }
        return null;
    }

    /**
     * Tags the ItemStack as a trinket using ValhallaTrinkets APIs via reflection
     */
    private static void tagAsTrinket(ItemStack stack, Integer typeId, Integer id, Boolean unique) throws Exception {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        Class<?> manager = Class.forName("me.athlaeos.valhallatrinkets.TrinketsManager");
        Class<?> props = Class.forName("me.athlaeos.valhallatrinkets.TrinketProperties");

        // Fetch TrinketType for given id
        Object typesMap = manager.getMethod("getTrinketTypes").invoke(null);
        if (typeId != null && typesMap instanceof Map){
            Object type = ((Map<?, ?>) typesMap).get(typeId);
            if (type != null){
                Method setType = manager.getMethod("setType", ItemMeta.class, Class.forName("me.athlaeos.valhallatrinkets.TrinketType"));
                setType.invoke(null, meta, type);
            }
        }

        if (id != null){
            Method setId = props.getMethod("setTrinketID", ItemMeta.class, Integer.class);
            setId.invoke(null, meta, id);
        }

        if (unique != null){
            Method setUnique = props.getMethod("setUniqueTrinket", ItemMeta.class, boolean.class);
            setUnique.invoke(null, meta, unique);
        }

        stack.setItemMeta(meta);
    }
}
