package runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ArmorInvulnBridge {

    private static final Set<String> GOD_ARMOR_TAGS = ConcurrentHashMap.newKeySet();

    static {
        GOD_ARMOR_TAGS.add("transfinityimproved:praetor_helmet");
        GOD_ARMOR_TAGS.add("transfinityimproved:praetor_chestplate");
        GOD_ARMOR_TAGS.add("transfinityimproved:praetor_leggings");
        GOD_ARMOR_TAGS.add("transfinityimproved:praetor_boots");
        GOD_ARMOR_TAGS.add("transfinityimproved:god_armor");
    }

    public static boolean shouldBlockDamage(String entityUUID, float damage, String armorTag) {
        if (LuaArmorEngine.isGodEntity(entityUUID)) return true;

        try {
            LuaArmorEngine luaEng = LuaArmorEngine.getInstance();
            if (luaEng != null && luaEng.vetoCheck(entityUUID, damage, armorTag)) return true;
        } catch (Exception e) {
            System.err.println("[ArmorInvulnBridge] Lua layer error: " + e.getMessage());
        }

        return GOD_ARMOR_TAGS.contains(armorTag);
    }

    public static boolean shouldBlockDamage(String entityUUID, float damage) {
        return shouldBlockDamage(entityUUID, damage, "unknown");
    }

    public static boolean isGodArmorTag(String tag) {
        return GOD_ARMOR_TAGS.contains(tag);
    }

    public static void registerArmorTag(String tag) {
        GOD_ARMOR_TAGS.add(tag);
        System.out.println("[ArmorInvulnBridge] Registered god armor tag: " + tag);
    }

    public static void registerGodEntity(String uuid) {
        LuaArmorEngine.registerGodEntity(uuid);
    }

    public static void unregisterGodEntity(String uuid) {
        LuaArmorEngine.unregisterGodEntity(uuid);
    }
}