package runtime;

import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public final class GodHelper {

    private GodHelper() {}

    private static final String PLAYER_CLASS = "net.minecraft.world.entity.player.Player";
    private static final String LIVING_ENTITY_CLASS = "net.minecraft.world.entity.LivingEntity";
    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    private static final String EQUIPMENT_SLOT_CLASS = "net.minecraft.world.entity.EquipmentSlot";
    private static final String ITEM_STACK_CLASS = "net.minecraft.world.item.ItemStack";
    private static final String SYNCHED_DATA_CLASS = "net.minecraft.network.syncher.SynchedEntityData";
    private static final String COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";
    private static final String AABB_CLASS = "net.minecraft.world.phys.AABB";
    private static final String LEVEL_CLASS = "net.minecraft.world.level.Level";
    private static final String RESOURCE_LOCATION_CLASS = "net.minecraft.resources.ResourceLocation";
    private static final String FORGE_REGISTRIES_CLASS = "net.minecraftforge.registries.ForgeRegistries";
    private static final String REMOVAL_REASON_CLASS = "net.minecraft.world.entity.Entity$RemovalReason";

    private static final String MOD_NAMESPACE = "transfinity_improved";
    private static final String[] PREATOR_ITEM_KEYS = {
            "preator_helmet", "preator_chestplate", "preator_leggings", "preator_boots"
    };
    private static final String[] ARMOR_SLOT_NAMES = {"HEAD", "CHEST", "LEGS", "FEET"};

    private static final double KILL_AURA_RADIUS = 64.0;
    private static final int KILL_AURA_INTERVAL = 1;

    // The mod's original DATA_GOD_MAP — moved here so the agent owns it.
    static final Map<Object, Object> DATA_GOD_MAP =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean itemsResolved = false;
    private static final Object[] PREATOR_ITEMS = new Object[4];

    private static volatile Class<?> playerClass;
    private static volatile boolean playerClassFailed = false;
    private static volatile Class<?> entityClass;
    private static volatile Class<?> livingClass;

    private static volatile Method mGetItemBySlot, mStackGetItem;
    private static volatile Method mGetHealth, mGetMaxHealth, mSetHealth;
    private static volatile Method mGetEntityData, mGetPersistentData, mLevel, mGetBoundingBox,
            mInflate, mGetType, mIsRemoved, mRemove;
    private static volatile Method mGetEntitiesOfClass;
    private static volatile Method mDataGet, mDataSet;
    private static volatile Method mTagGetBoolean, mTagPutInt, mTagPutFloat;
    private static volatile Method mGetNamespace;
    private static volatile Method mRegistryGetValue, mRegistryGetKey;

    private static volatile Field fTickCount, fIsClientSide;
    private static volatile Object removalKilled;

    private static volatile Object[] slotConstants;
    private static volatile Object itemsRegistry, entityTypesRegistry;
    private static volatile Object healthAccessorRef, novelizedAccessorRef;
    private static volatile boolean healthAccessorSearched, novelizedAccessorSearched;

    private static Class<?> loadClass(String name, Object ctx) {
        ClassLoader[] candidates = {
                ctx != null ? ctx.getClass().getClassLoader() : null,
                Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method resolveMethod(Class<?> cls, String desc, String... preferredNames) {
        Method fallback = null;
        for (Method m : cls.getMethods()) {
            if (!Type.getMethodDescriptor(m).equals(desc)) continue;
            for (String n : preferredNames) {
                if (m.getName().equals(n)) return m;
            }
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    private static Field resolveField(Class<?> cls, String[] names, Class<?> type) {
        for (String n : names) {
            try {
                return cls.getField(n);
            } catch (Throwable ignored) {}
        }
        if (type != null) {
            for (Field f : cls.getFields()) {
                if (f.getType() == type) return f;
            }
        }
        return null;
    }

    public static boolean isGod(Object entity) {
        if (entity == null) return false;
        try {
            Class<?> pc = playerClass(entity);
            if (pc == null || !pc.isAssignableFrom(entity.getClass())) return false;
            return hasFullPreatorSet(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isGodEntity(Object entity) {
        try {
            if (entity != null) {
                Object pd = getPersistentData(entity);
                if (pd != null && Boolean.TRUE.equals(tagGetBoolean(pd, "titandeathtag"))) {
                    return false; // titans flagged for custom death are allowed to discard()
                }
                if (isLiving(entity)) return isGod(entity);
            }
        } catch (Throwable t) {
            // never crash
        }
        return false;
    }

    public static void godTick(Object entity) {
        try {
            if (!isGod(entity)) {
                Object data = getEntityData(entity);
                if (data != null) DATA_GOD_MAP.remove(data);
                return;
            }

            Object data = getEntityData(entity);
            if (data != null) DATA_GOD_MAP.put(data, entity);

            clearNovelizedFlag(entity);

            float max = getMaxHealth(entity);
            if (getHealth(entity) < max) {
                forceRestoreHealth(entity, max);
            }

            resetOmniMobsDetection(entity);

            if (!isClientSide(entity) && (tickCount(entity) % KILL_AURA_INTERVAL) == 0) {
                applyKillAura(entity);
            }
        } catch (Throwable t) {
            // never crash during tick
        }
    }

    public static float safeSetHealth(Object entity, float value) {
        try {
            if (isGod(entity)) {
                float max = getMaxHealth(entity);
                float safeMax = (max > 0f && !Float.isNaN(max)) ? max : 20f;
                if (Float.isNaN(value) || value <= 0f) return safeMax;
                return Math.max(value, 1f);
            }
        } catch (Throwable t) {
            // defensive — fall through to general sanitization
        }
        if (Float.isNaN(value)) return 0f;
        if (Float.isInfinite(value) && value < 0f) return 0f;
        return value;
    }

    public static float godGetHealth(Object entity) {
        try {
            if (isGod(entity)) return getMaxHealth(entity);
        } catch (Throwable t) {
            // fall through
        }
        return -1f; // sentinel: use the original getHealth() value
    }

    public static void restoreHealth(Object entity) {
        try {
            float max = getMaxHealth(entity);
            if (max > 0f && !Float.isNaN(max)) {
                forceRestoreHealth(entity, max);
            }
        } catch (Throwable t) {
            // never crash
        }
    }

    private static void forceRestoreHealth(Object entity, float health) {
        setHealth(entity, health);
        Object data = getEntityData(entity);
        Object accessor = getHealthAccessor(entity);
        if (data != null && accessor != null) {
            dataSet(data, accessor, health);
        }
    }

    public static Object interceptEntityDataSet(Object data, Object accessor, Object value) {
        try {
            Object god = DATA_GOD_MAP.get(data);
            if (god != null) {
                Object hAccessor = getHealthAccessor(god);
                if (hAccessor != null && accessor.equals(hAccessor)) {
                    if (value instanceof Float incoming) {
                        if (Float.isNaN(incoming) || incoming < getMaxHealth(god)) {
                            return getMaxHealth(god);
                        }
                    }
                }

                Object nAccessor = getNovelizedAccessor(god);
                if (nAccessor != null && accessor.equals(nAccessor)) {
                    byte incoming;
                    if (value instanceof Byte b) {
                        incoming = b;
                    } else if (value instanceof Boolean bool) {
                        incoming = (byte) (bool ? 1 : 0);
                    } else {
                        incoming = 0;
                    }
                    if (incoming != 0) {
                        return (byte) 0;
                    }
                }
            }

            return sanitize(value);
        } catch (Throwable t) {
            return value;
        }
    }

    public static boolean shouldBlockGameMode(Object entity, Object gameType) {
        try {
            if (entity == null || gameType == null) return false;
            if (!isLiving(entity)) return false;
            if (!isGod(entity)) return false;
            // toString() on the GameType enum returns its name
            return gameType.toString().contains("SPECTATOR");
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object sanitize(Object value) {
        try {
            if (value instanceof Float f) {
                if (Float.isNaN(f)) return 0f;
                if (Float.isInfinite(f) && f < 0f) return 0f;
            }
        } catch (Throwable t) {
            return value;
        }
        return value;
    }

    private static boolean hasFullPreatorSet(Object entity) {
        ensureItems(entity);
        if (!itemsResolved) return false;
        Object[] slots = slotConstants(entity);
        if (slots == null) return false;
        for (int i = 0; i < 4; i++) {
            Object stack = getItemBySlot(entity, slots[i]);
            Object item = stack == null ? null : stackItem(stack);
            if (item == null || item != PREATOR_ITEMS[i]) return false;
        }
        return true;
    }

    private static Object[] slotConstants(Object ctx) {
        Object[] slots = slotConstants;
        if (slots == null) {
            Class<?> c = loadClass(EQUIPMENT_SLOT_CLASS, ctx);
            if (c == null) return null;
            Object[] tmp = new Object[4];
            for (int i = 0; i < 4; i++) {
                try {
                    tmp[i] = c.getField(ARMOR_SLOT_NAMES[i]).get(null);
                } catch (Throwable t) {
                    return null;
                }
            }
            slotConstants = tmp;
        }
        return slotConstants;
    }

    private static void ensureItems(Object ctx) {
        if (itemsResolved) return;
        try {
            Class<?> forgeRegistries = loadClass(FORGE_REGISTRIES_CLASS, ctx);
            if (forgeRegistries == null) return; // too early — retry on next call
            itemsRegistry = forgeRegistries.getField("ITEMS").get(null);
            entityTypesRegistry = forgeRegistries.getField("ENTITY_TYPES").get(null);
            if (itemsRegistry == null) return;

            mRegistryGetValue = resolveMethod(itemsRegistry.getClass(),
                    "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;", "getValue");
            mRegistryGetKey = resolveMethod(entityTypesRegistry.getClass(),
                    "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;", "getKey");
            if (mRegistryGetValue == null || mRegistryGetKey == null) return;

            Class<?> rl = loadClass(RESOURCE_LOCATION_CLASS, ctx);
            if (rl == null) return;
            java.lang.reflect.Constructor<?> ctor = rl.getConstructor(String.class, String.class);

            for (int i = 0; i < PREATOR_ITEM_KEYS.length; i++) {
                Object loc = ctor.newInstance(MOD_NAMESPACE, PREATOR_ITEM_KEYS[i]);
                PREATOR_ITEMS[i] = mRegistryGetValue.invoke(itemsRegistry, loc);
            }
            itemsResolved = true;
            System.out.println("[GodHelper] Resolved Preator god-armor items from registry");
        } catch (Throwable t) {
            // leave itemsResolved false — retry on the next call
        }
    }

    private static Class<?> playerClass(Object ctx) {
        Class<?> pc = playerClass;
        if (pc == null && !playerClassFailed) {
            pc = loadClass(PLAYER_CLASS, ctx);
            if (pc == null) playerClassFailed = true;
            else playerClass = pc;
        }
        return pc;
    }

    private static Class<?> entityClass(Object ctx) {
        Class<?> c = entityClass;
        if (c == null) {
            c = loadClass(ENTITY_CLASS, ctx);
            entityClass = c;
        }
        return c;
    }

    private static Class<?> livingClass(Object ctx) {
        Class<?> c = livingClass;
        if (c == null) {
            c = loadClass(LIVING_ENTITY_CLASS, ctx);
            livingClass = c;
        }
        return c;
    }

    private static boolean isLiving(Object entity) {
        Class<?> lc = livingClass(entity);
        return lc != null && lc.isAssignableFrom(entity.getClass());
    }

    private static Object getItemBySlot(Object entity, Object slot) {
        try {
            if (mGetItemBySlot == null) {
                Class<?> lc = livingClass(entity);
                if (lc == null) return null;
                mGetItemBySlot = resolveMethod(lc,
                        "(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;",
                        "getItemBySlot", "m_6844_");
            }
            return mGetItemBySlot == null ? null : mGetItemBySlot.invoke(entity, slot);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object stackItem(Object stack) {
        try {
            if (mStackGetItem == null) {
                Class<?> sc = loadClass(ITEM_STACK_CLASS, stack);
                if (sc == null) return null;
                mStackGetItem = resolveMethod(sc, "()Lnet/minecraft/world/item/Item;", "getItem", "m_41720_");
            }
            return mStackGetItem == null ? null : mStackGetItem.invoke(stack);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float getHealth(Object entity) {
        try {
            if (mGetHealth == null) {
                Class<?> lc = livingClass(entity);
                if (lc == null) return 0f;
                mGetHealth = resolveMethod(lc, "()F", "getHealth", "m_21223_");
            }
            return mGetHealth == null ? 0f : ((Number) mGetHealth.invoke(entity)).floatValue();
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float getMaxHealth(Object entity) {
        try {
            if (mGetMaxHealth == null) {
                Class<?> lc = livingClass(entity);
                if (lc == null) return 20f;
                mGetMaxHealth = resolveMethod(lc, "()F", "getMaxHealth", "m_21233_");
            }
            return mGetMaxHealth == null ? 20f : ((Number) mGetMaxHealth.invoke(entity)).floatValue();
        } catch (Throwable t) {
            return 20f;
        }
    }

    private static void setHealth(Object entity, float value) {
        try {
            if (mSetHealth == null) {
                Class<?> lc = livingClass(entity);
                if (lc == null) return;
                mSetHealth = resolveMethod(lc, "(F)V", "setHealth", "m_21153_");
            }
            if (mSetHealth != null) mSetHealth.invoke(entity, value);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static Object getEntityData(Object entity) {
        try {
            if (mGetEntityData == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return null;
                mGetEntityData = resolveMethod(ec,
                        "()Lnet/minecraft/network/syncher/SynchedEntityData;", "getEntityData", "m_20088_");
            }
            return mGetEntityData == null ? null : mGetEntityData.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getPersistentData(Object entity) {
        try {
            if (mGetPersistentData == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return null;
                mGetPersistentData = resolveMethod(ec,
                        "()Lnet/minecraft/nbt/CompoundTag;", "getPersistentData", "m_20010_");
            }
            return mGetPersistentData == null ? null : mGetPersistentData.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object levelOf(Object entity) {
        try {
            if (mLevel == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return null;
                mLevel = resolveMethod(ec, "()Lnet/minecraft/world/level/Level;", "level", "m_9236_");
            }
            return mLevel == null ? null : mLevel.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getBoundingBox(Object entity) {
        try {
            if (mGetBoundingBox == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return null;
                mGetBoundingBox = resolveMethod(ec, "()Lnet/minecraft/world/phys/AABB;", "getBoundingBox", "m_20191_");
            }
            return mGetBoundingBox == null ? null : mGetBoundingBox.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object inflateAabb(Object aabb, double amount) {
        try {
            if (mInflate == null) {
                Class<?> ac = loadClass(AABB_CLASS, aabb);
                if (ac == null) return null;
                mInflate = resolveMethod(ac, "(D)Lnet/minecraft/world/phys/AABB;", "inflate", "m_82406_");
            }
            return mInflate == null ? null : mInflate.invoke(aabb, amount);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int tickCount(Object entity) {
        try {
            if (fTickCount == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return 0;
                fTickCount = resolveField(ec, new String[]{"tickCount", "f_19797_"}, int.class);
            }
            return fTickCount == null ? 0 : fTickCount.getInt(entity);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isClientSide(Object entity) {
        try {
            Object level = levelOf(entity);
            if (level == null) return true; // unknown -> assume client-safe (skip kill aura)
            if (fIsClientSide == null) {
                Class<?> lc = loadClass(LEVEL_CLASS, level);
                if (lc == null) return true;
                fIsClientSide = resolveField(lc, new String[]{"isClientSide", "f_46443_"}, boolean.class);
            }
            return fIsClientSide == null || fIsClientSide.getBoolean(level);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void applyKillAura(Object god) {
        try {
            Object level = levelOf(god);
            Object bb = getBoundingBox(god);
            Object inflated = inflateAabb(bb, KILL_AURA_RADIUS);
            Class<?> living = livingClass(god);
            if (level == null || inflated == null || living == null) return;

            List<?> targets = getEntitiesOfClass(level, living, inflated);
            if (targets == null) return;
            for (Object target : targets) {
                if (target == god) continue;
                if (isGod(target)) continue;   // spare other god-armour wearers
                if (isRemoved(target)) continue;
                if (tickCount(target) <= 2) continue; // must have been alive > 2 ticks
                if (isFriendlyMod(target)) continue;  // spare transfinity + chaosmobs entities
                removeEntity(target);
            }
        } catch (Throwable t) {
            // never crash during tick
        }
    }

    private static List<?> getEntitiesOfClass(Object level, Class<?> entityTypeClass, Object aabb) {
        try {
            if (mGetEntitiesOfClass == null) {
                Class<?> lc = loadClass(LEVEL_CLASS, level);
                if (lc == null) return null;
                mGetEntitiesOfClass = resolveMethod(lc,
                        "(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
                        "getEntitiesOfClass", "m_45933_");
            }
            if (mGetEntitiesOfClass == null) return null;
            Object list = mGetEntitiesOfClass.invoke(level, entityTypeClass, aabb, ACCEPT_ALL);
            return list instanceof List<?> l ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Predicate<Object> ACCEPT_ALL = o -> true;

    private static boolean isRemoved(Object entity) {
        try {
            if (mIsRemoved == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return true;
                mIsRemoved = resolveMethod(ec, "()Z", "isRemoved", "m_213877_");
            }
            return mIsRemoved == null || Boolean.TRUE.equals(mIsRemoved.invoke(entity));
        } catch (Throwable t) {
            return true;
        }
    }

    private static void removeEntity(Object entity) {
        try {
            if (removalKilled == null) {
                Class<?> rc = loadClass(REMOVAL_REASON_CLASS, entity);
                if (rc == null) return;
                Object k = Enum.valueOf((Class) rc, "KILLED");
                removalKilled = k;
            }
            if (mRemove == null) {
                Class<?> ec = entityClass(entity);
                if (ec == null) return;
                mRemove = resolveMethod(ec,
                        "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "remove", "m_142687_");
            }
            if (mRemove != null) mRemove.invoke(entity, removalKilled);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static boolean isFriendlyMod(Object target) {
        try {
            Object key = entityTypeKey(target);
            if (key == null) return false;
            String ns = namespaceOf(key);
            return ns.equals("transfinity_improved") || ns.equals("chaosmobs");
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object entityTypeKey(Object target) {
        try {
            if (mGetType == null) {
                Class<?> ec = entityClass(target);
                if (ec == null) return null;
                mGetType = resolveMethod(ec, "()Lnet/minecraft/world/entity/EntityType;", "getType", "m_6095_");
            }
            if (mGetType == null || entityTypesRegistry == null) return null;
            Object type = mGetType.invoke(target);
            if (type == null) return null;
            return mRegistryGetKey.invoke(entityTypesRegistry, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String namespaceOf(Object resourceLocation) {
        try {
            if (mGetNamespace == null) {
                Class<?> rc = loadClass(RESOURCE_LOCATION_CLASS, resourceLocation);
                if (rc == null) return "";
                mGetNamespace = resolveMethod(rc, "()Ljava/lang/String;", "getNamespace", "m_135827_");
            }
            return mGetNamespace == null ? "" : String.valueOf(mGetNamespace.invoke(resourceLocation));
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object getHealthAccessor(Object ctx) {
        if (healthAccessorSearched) return healthAccessorRef;
        healthAccessorSearched = true;
        Class<?> lc = livingClass(ctx);
        if (lc == null) return null;
        for (String name : new String[]{"DATA_HEALTH_ID", "f_20961_"}) {
            try {
                Field f = lc.getField(name);
                healthAccessorRef = f.get(null);
                return healthAccessorRef;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getNovelizedAccessor(Object ctx) {
        if (novelizedAccessorSearched) return novelizedAccessorRef;
        novelizedAccessorSearched = true;
        try {
            Class<?> ec = entityClass(ctx);
            Class<?> accessorCls = loadClass("net.minecraft.network.syncher.EntityDataAccessor", ctx);
            if (ec == null || accessorCls == null) return null;
            for (Field f : ec.getDeclaredFields()) {
                if (f.getType() == accessorCls
                        && f.getName().toUpperCase(Locale.ROOT).contains("NOVELIZED")) {
                    f.setAccessible(true);
                    novelizedAccessorRef = f.get(null);
                    break;
                }
            }
        } catch (Throwable ignored) {}
        return novelizedAccessorRef;
    }

    private static Object dataGet(Object data, Object accessor) {
        try {
            if (mDataGet == null) {
                Class<?> dc = loadClass(SYNCHED_DATA_CLASS, data);
                if (dc == null) return null;
                mDataGet = resolveMethod(dc,
                        "(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;",
                        "get", "m_135370_");
            }
            return mDataGet == null ? null : mDataGet.invoke(data, accessor);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dataSet(Object data, Object accessor, Object value) {
        try {
            if (mDataSet == null) {
                Class<?> dc = loadClass(SYNCHED_DATA_CLASS, data);
                if (dc == null) return;
                mDataSet = resolveMethod(dc,
                        "(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V",
                        "set", "m_135381_");
            }
            if (mDataSet != null) mDataSet.invoke(data, accessor, value);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void clearNovelizedFlag(Object entity) {
        try {
            Object accessor = getNovelizedAccessor(entity);
            Object data = getEntityData(entity);
            if (accessor == null || data == null) return;
            Object val = dataGet(data, accessor);
            if (val instanceof Number n && n.byteValue() != 0) {
                dataSet(data, accessor, (byte) 0);
            }
        } catch (Throwable ignored) {}
    }

    private static void resetOmniMobsDetection(Object entity) {
        try {
            Object pd = getPersistentData(entity);
            if (pd == null) return;
            tagPutInt(pd, "omnimobs_time_data", tickCount(entity));
            tagPutInt(pd, "omnimobs_counter_data", 0);
            tagPutFloat(pd, "omnimobs_health_data", getMaxHealth(entity));
        } catch (Throwable ignored) {}
    }

    private static Boolean tagGetBoolean(Object tag, String key) {
        try {
            if (mTagGetBoolean == null) {
                Class<?> tc = loadClass(COMPOUND_TAG_CLASS, tag);
                if (tc == null) return null;
                mTagGetBoolean = resolveMethod(tc, "(Ljava/lang/String;)Z", "getBoolean", "m_128471_");
            }
            return mTagGetBoolean == null ? null : (Boolean) mTagGetBoolean.invoke(tag, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void tagPutInt(Object tag, String key, int value) {
        try {
            if (mTagPutInt == null) {
                Class<?> tc = loadClass(COMPOUND_TAG_CLASS, tag);
                if (tc == null) return;
                mTagPutInt = resolveMethod(tc, "(Ljava/lang/String;I)V", "putInt", "m_128356_");
            }
            if (mTagPutInt != null) mTagPutInt.invoke(tag, key, value);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void tagPutFloat(Object tag, String key, float value) {
        try {
            if (mTagPutFloat == null) {
                Class<?> tc = loadClass(COMPOUND_TAG_CLASS, tag);
                if (tc == null) return;
                mTagPutFloat = resolveMethod(tc, "(Ljava/lang/String;F)V", "putFloat", "m_128346_");
            }
            if (mTagPutFloat != null) mTagPutFloat.invoke(tag, key, value);
        } catch (Throwable t) {
            // ignore
        }
    }
}