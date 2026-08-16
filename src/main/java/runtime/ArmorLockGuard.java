package runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ArmorLockGuard {

    private static final Set<Integer> PROTECTED_ENTITY_IDS = ConcurrentHashMap.newKeySet();
    private static volatile boolean TRACKING_ACTIVE = false;
    private static final String FORGE_REGISTRIES_CLASS = "net.minecraftforge.registries.ForgeRegistries";
    private static final String ITEM_STACK_CLASS = "net.minecraft.world.item.ItemStack";
    private static final Map<List<?>, Integer> ARMOR_LIST_TO_ENTITY =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Integer, Object[]> CANONICAL_ARMOR = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> SWEEP_COUNTER = new ConcurrentHashMap<>();
    private static volatile boolean RESTORING_ARMOR = false;
    private static final Object NO_PROBE = new Object();
    private static final Map<String, Object> PLAYER_PROBE = new ConcurrentHashMap<>();
    private static Method ST_EMPTY, ST_ITEM, ST_COUNT, ST_COPY, ST_SET_COUNT;
    private static Method SLOT_GET_TYPE, SLOT_GET_INDEX;
    private static Field ST_EMPTY_FIELD, ARMOR_LIST_FIELD;
    private static Object EMPTY_STACK;
    private static boolean stackOpsReady = false;

    public static void registerProtectedEntityId(int id) {
        if (!TrustChecker.calledFromTrustedCode()) {
            AgentLog.log("[ArmorLockGuard] Blocked untrusted attempt to register protected entity id " + id);
            return;
        }
        PROTECTED_ENTITY_IDS.add(id);
        TRACKING_ACTIVE = true;
    }

    public static void unregisterProtectedEntityId(int id) {
        if (!TrustChecker.calledFromTrustedCode()) {
            AgentLog.log("[ArmorLockGuard] Blocked untrusted attempt to unregister protected entity id " + id);
            return;
        }
        PROTECTED_ENTITY_IDS.remove(id);
        if (PROTECTED_ENTITY_IDS.isEmpty()) TRACKING_ACTIVE = false;
    }

    private static Class<?> resolveClass(String name, Object contextObject) {
        ClassLoader[] candidates = {
                contextObject != null ? contextObject.getClass().getClassLoader() : null,
                Thread.currentThread().getContextClassLoader(),
                ArmorLockGuard.class.getClassLoader()
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void trackEntityGodStatus(Object livingEntity) {
        try {
            if (livingEntity == null) return;
            boolean isGod = GodHelper.isGod(livingEntity);

            int id = entityId(livingEntity);
            if (id < 0) return;

            Method getInv = probeGetInventory(livingEntity);
            boolean isPlayer = getInv != null;
            if (isPlayer) {
                if (isGod) {
                    Object inventory = getInv.invoke(livingEntity);
                    Object armorList = getArmorList(inventory);
                    if (armorList instanceof List<?> list) {
                        if (!Integer.valueOf(id).equals(ARMOR_LIST_TO_ENTITY.get(list))) {
                            ARMOR_LIST_TO_ENTITY.put(list, id);
                        }
                        Object[] canonical = CANONICAL_ARMOR.get(id);
                        if (canonical == null) {
                            CANONICAL_ARMOR.put(id, readSlots(list));
                        } else {
                            reassertArmor(inventory, list, id, canonical);
                        }
                    }
                    registerProtectedEntityId(id);
                } else {
                    Object[] canonical = CANONICAL_ARMOR.get(id);
                    if (canonical != null && !allSlotsEmpty(canonical)) {
                        Object inventory = getInv.invoke(livingEntity);
                        Object armorList = getArmorList(inventory);
                        if (armorList instanceof List<?> list) {
                            if (!Integer.valueOf(id).equals(ARMOR_LIST_TO_ENTITY.get(list))) {
                                ARMOR_LIST_TO_ENTITY.put(list, id);
                            }
                            reassertArmor(inventory, list, id, canonical);
                        }
                        registerProtectedEntityId(id);
                    } else {
                        untrackPlayerArmor(id);
                        unregisterProtectedEntityId(id);
                    }
                }
            } else {
                if (isGod) registerProtectedEntityId(id);
                else unregisterProtectedEntityId(id);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void untrackEntity(Object entity) {
        try {
            if (entity == null) return;
            int id = entityId(entity);
            unregisterProtectedEntityId(id);
            untrackPlayerArmor(id);
        } catch (Throwable ignored) {}
    }

    private static void untrackPlayerArmor(Integer id) {
        if (id == null) return;
        CANONICAL_ARMOR.remove(id);
        SWEEP_COUNTER.remove(id);
        synchronized (ARMOR_LIST_TO_ENTITY) {
            ARMOR_LIST_TO_ENTITY.entrySet().removeIf(e -> e.getValue() != null && e.getValue().equals(id));
        }
    }

    private static Method probeGetInventory(Object entity) {
        String name = entity.getClass().getName();
        Object v = PLAYER_PROBE.get(name);
        if (v == null) {
            Method m = resolve(entity.getClass(), new String[]{"m_150109_", "getInventory"});
            v = m != null ? m : NO_PROBE;
            PLAYER_PROBE.put(name, v);
        }
        return v == NO_PROBE ? null : (Method) v;
    }

    private static Object inventoryOf(Object entity) {
        Method m = probeGetInventory(entity);
        if (m == null) return null;
        try {
            return m.invoke(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getArmorList(Object inventory) {
        try {
            Field f = ARMOR_LIST_FIELD;
            if (f == null) {
                Class<?> c = inventory.getClass();
                while (c != null) {
                    try {
                        f = c.getDeclaredField("f_35975_");
                        break;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                if (f == null) {
                    try {
                        f = inventory.getClass().getField("armor");
                    } catch (Throwable ignored) {}
                }
                if (f == null) return null;
                f.setAccessible(true);
                ARMOR_LIST_FIELD = f;
            }
            return f.get(inventory);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<?> asList(Object maybeList) {
        return maybeList instanceof List<?> list ? list : null;
    }

    private static synchronized void ensureStackOps() {
        if (stackOpsReady) return;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> stackCls = Class.forName(ITEM_STACK_CLASS, false, cl);
            ST_EMPTY = resolve(stackCls, new String[]{"m_41619_", "isEmpty"});
            ST_ITEM = resolve(stackCls, new String[]{"m_41720_", "getItem"});
            ST_COUNT = resolve(stackCls, new String[]{"m_41613_", "getCount"});
            ST_COPY = resolve(stackCls, new String[]{"m_41777_", "copy"});
            ST_SET_COUNT = resolve(stackCls, new String[]{"m_41764_", "setCount"}, int.class);
            try {
                ST_EMPTY_FIELD = stackCls.getField("f_41583_");
            } catch (Throwable t) {
                try {
                    ST_EMPTY_FIELD = stackCls.getField("EMPTY");
                } catch (Throwable ignored) {}
            }
            if (ST_EMPTY_FIELD != null) EMPTY_STACK = ST_EMPTY_FIELD.get(null);

            Class<?> slotCls = Class.forName("net.minecraft.world.entity.EquipmentSlot", false, cl);
            SLOT_GET_TYPE = resolve(slotCls, new String[]{"m_20743_", "getType"});
            SLOT_GET_INDEX = resolve(slotCls, new String[]{"m_20749_", "getIndex"});
            stackOpsReady = true;
        } catch (Throwable t) {
            AgentLog.log("[ArmorLockGuard] stack-ops reflection failed: " + t);
        }
    }

    private static Method resolve(Class<?> cls, String[] names, Class<?>... params) {
        for (String n : names) {
            try {
                return cls.getMethod(n, params);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isStackEmpty(Object s) {
        if (s == null) return true;
        try {
            ensureStackOps();
            return ST_EMPTY == null || Boolean.TRUE.equals(ST_EMPTY.invoke(s));
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object stackItem(Object s) {
        try {
            ensureStackOps();
            return ST_ITEM == null ? null : ST_ITEM.invoke(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int stackCount(Object s) {
        try {
            ensureStackOps();
            return ST_COUNT == null ? 0 : ((Number) ST_COUNT.invoke(s)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object copyStack(Object s) {
        try {
            ensureStackOps();
            return ST_COPY == null ? null : ST_COPY.invoke(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean stacksMatch(Object canon, Object cur) {
        if (canon == null) return isStackEmpty(cur);
        if (isStackEmpty(cur)) return false;
        return stackItem(canon) == stackItem(cur) && stackCount(canon) == stackCount(cur);
    }

    private static Object[] readSlots(List<?> armorList) {
        Object[] arr = new Object[4];
        if (armorList == null) return arr;
        for (int i = 0; i < 4; i++) {
            Object s = armorList.get(i);
            arr[i] = isStackEmpty(s) ? null : copyStack(s);
        }
        return arr;
    }

    private static boolean allSlotsEmpty(Object[] canonical) {
        if (canonical == null) return true;
        for (Object s : canonical) {
            if (s != null) return false;
        }
        return true;
    }

    private static void updateCanonical(Integer id, int idx, Object stack) {
        if (id == null || idx < 0 || idx > 3) return;
        Object[] arr = CANONICAL_ARMOR.get(id);
        if (arr == null) return;
        arr[idx] = isStackEmpty(stack) ? null : copyStack(stack);
    }

    private static void updateCanonicalAfterSplit(Integer id, int idx, Object stack, int amount) {
        if (id == null) return;
        try {
            int newCount = stackCount(stack) - amount;
            if (newCount <= 0) {
                updateCanonical(id, idx, null);
                return;
            }
            Object c = copyStack(stack);
            if (c != null && ST_SET_COUNT != null) ST_SET_COUNT.invoke(c, newCount);
            updateCanonical(id, idx, c);
        } catch (Throwable t) {
        }
    }

    private static int indexOfIdentity(List<?> list, Object stack) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == stack) return i;
        }
        return -1;
    }

    private static void reassertArmor(Object inventory, List<?> armorList, Integer id, Object[] canonical) {
        int tick = SWEEP_COUNTER.merge(id, 1, Integer::sum);
        if (tick % 5 != 0) return;

        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            if (!stacksMatch(canonical[i], armorList.get(i))) {
                changed = true;
                break;
            }
        }
        if (!changed) return;

        RESTORING_ARMOR = true;
        try {
            Method setItem = null;
            for (Method mm : inventory.getClass().getMethods()) {
                if ((mm.getName().equals("m_6836_") || mm.getName().equals("setItem"))
                        && mm.getParameterCount() == 2
                        && mm.getParameterTypes()[0] == int.class) {
                    setItem = mm;
                    break;
                }
            }
            if (setItem == null) return;
            boolean restored = false;
            for (int i = 0; i < 4; i++) {
                if (stacksMatch(canonical[i], armorList.get(i))) continue;
                Object target = canonical[i] != null ? canonical[i] : EMPTY_STACK;
                if (target == null) continue;
                setItem.invoke(inventory, 36 + i, target);
                restored = true;
            }
            if (restored) {
                AgentLog.log("[ArmorLockGuard] Re-asserted armor for protected player " + id);
            }
        } catch (Throwable t) {
            AgentLog.log("[ArmorLockGuard] re-assert failed: " + t);
        } finally {
            RESTORING_ARMOR = false;
        }
    }

    public static boolean isProtectedItemStack(Object itemStack) {
        try {
            if (itemStack == null) return false;
            if (isStackEmpty(itemStack)) return false;

            Object item = stackItem(itemStack);
            if (item == null) return false;

            Class<?> forgeRegistriesCls = resolveClass(FORGE_REGISTRIES_CLASS, item);
            if (forgeRegistriesCls == null) return false;
            Object itemsRegistry = forgeRegistriesCls.getField("ITEMS").get(null);

            Object key = null;
            for (Method m : itemsRegistry.getClass().getMethods()) {
                if (m.getName().equals("getKey") && m.getParameterCount() == 1
                        && m.getReturnType().getName().equals("net.minecraft.resources.ResourceLocation")
                        && m.getParameterTypes()[0].isAssignableFrom(item.getClass())) {
                    key = m.invoke(itemsRegistry, item);
                    break;
                }
            }
            if (key == null) return false;

            return ArmorInvulnBridge.isGodArmorTag(key.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isCallerContainerClick() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.contains("ContainerMenu") || cls.contains("AbstractContainerMenu")) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldBlockSetItem(Object inventory, int slot, Object currentStack, Object newStack) {
        try {
            if (RESTORING_ARMOR) return false;
            if (slot < 36 || slot > 39) return false; // only armor slots
            Object armorList = getArmorList(inventory);
            if (armorList == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(armorList);
            if (id == null) return false;

            int localIndex = slot - 36;
            if (!isProtectedItemStack(currentStack)) {
                if (newStack != null && isProtectedItemStack(newStack)) {
                    updateCanonical(id, localIndex, newStack);
                }
                return false;
            }
            if (currentStack == newStack) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, localIndex, newStack);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked non-container armor slot write (setItem)");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockSetItemSlot(Object player, Object slot, Object currentStack, Object newStack) {
        try {
            if (RESTORING_ARMOR) return false;
            Object inv = inventoryOf(player);
            if (inv == null) return false;
            Object armorList = getArmorList(inv);
            if (armorList == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(armorList);
            if (id == null) return false;

            int localIndex = armorSlotIndex(slot);
            if (localIndex < 0) return false; // mainhand/offhand — not our concern

            if (!isProtectedItemStack(currentStack)) {
                if (newStack != null && isProtectedItemStack(newStack)) {
                    updateCanonical(id, localIndex, newStack);
                }
                return false;
            }
            if (currentStack == newStack) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, localIndex, newStack);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked non-container armor slot write (setItemSlot)");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int entityId(Object entity) {
        try {
            if (entity == null) return -1;
            Method m = resolve(entity.getClass(), new String[]{"m_19879_", "getId"});
            if (m == null) return -1;
            return (int) m.invoke(entity);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int armorSlotIndex(Object slot) {
        try {
            ensureStackOps();
            if (slot == null || SLOT_GET_TYPE == null) return -1;
            Object type = SLOT_GET_TYPE.invoke(slot);
            if (type == null || !String.valueOf(type).contains("ARMOR")) return -1;
            if (SLOT_GET_INDEX == null) return -1;
            return ((Number) SLOT_GET_INDEX.invoke(slot)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean shouldBlockInventoryRemoveNoUpdate(Object inventory, int slot, Object stack) {
        try {
            if (RESTORING_ARMOR) return false;
            if (slot < 36 || slot > 39) return false;
            Object armorList = getArmorList(inventory);
            if (armorList == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(armorList);
            if (id == null) return false;
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, slot - 36, null);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked removeItemNoUpdate of protected armor");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockInventoryRemoveStack(Object inventory, Object stack) {
        try {
            if (RESTORING_ARMOR) return false;
            Object armorList = getArmorList(inventory);
            if (armorList == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(armorList);
            if (id == null) return false;
            if (!isProtectedItemStack(stack)) return false;
            int idx = indexOfIdentity(asList(armorList), stack);
            if (idx < 0) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, idx, null);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked identity removal of protected armor");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockContainerHelperRemove(Object list, int index, int amount) {
        try {
            if (RESTORING_ARMOR) return false;
            if (list == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(list);
            if (id == null) return false;
            List<?> l = asList(list);
            if (l == null || index < 0 || index >= l.size()) return false;
            Object stack = l.get(index);
            if (isStackEmpty(stack)) return false;
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) {
                updateCanonicalAfterSplit(id, index, stack, amount);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked ContainerHelper.removeItem of protected armor");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockContainerHelperRemoveNoUpdate(Object list, int index) {
        try {
            if (RESTORING_ARMOR) return false;
            if (list == null) return false;
            Integer id = ARMOR_LIST_TO_ENTITY.get(list);
            if (id == null) return false;
            List<?> l = asList(list);
            if (l == null || index < 0 || index >= l.size()) return false;
            Object stack = l.get(index);
            if (isStackEmpty(stack)) return false;
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, index, null);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked ContainerHelper.removeItemNoUpdate of protected armor");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockContainerClear(Object container) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (container == null) return false;
            Object armorList = getArmorList(container);
            if (armorList == null) return false; // not a player Inventory (crafting grid etc.)
            if (ARMOR_LIST_TO_ENTITY.get(armorList) == null) return false;
            List<?> l = asList(armorList);
            if (l == null) return false;
            for (int i = 0; i < Math.min(4, l.size()); i++) {
                if (!isProtectedItemStack(l.get(i))) continue;
                if (isCallerContainerClick()) return false;
                AgentLog.log("[ArmorLockGuard] Blocked ContainerHelper.clearOrCountMatchingItems of protected armor");
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockStackClear(Object stack) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (stack == null) return false;
            if (!isTrackedArmorStack(stack)) return false;
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) return false;
            AgentLog.log("[ArmorLockGuard] Blocked ContainerHelper.clearOrCountMatchingItems of protected armor stack");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockRawArmorListWrite(Object list, int index, Object newStack) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (list == null) return false;
            if (index < 0 || index > 3) return false; // only the 4 armor slots of a tracked armor list
            Integer id = ARMOR_LIST_TO_ENTITY.get(list);
            if (id == null) return false;
            List<?> l = asList(list);
            if (l == null || index >= l.size()) return false;
            Object current = l.get(index);
            if (!isProtectedItemStack(current)) {
                if (newStack != null && isProtectedItemStack(newStack)) {
                    updateCanonical(id, index, newStack);
                }
                return false;
            }
            if (current == newStack) return false;
            if (isCallerContainerClick()) {
                updateCanonical(id, index, newStack);
                return false;
            }
            AgentLog.log("[ArmorLockGuard] Blocked raw NonNullList armor-slot write (avatar strip)");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockSetCount(Object stack, int newCount) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (stack == null) return false;
            if (!isTrackedArmorStack(stack)) return false;
            if (newCount >= stackCount(stack)) return false; // only reductions
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) return false;
            AgentLog.log("[ArmorLockGuard] Blocked setCount reduction of protected armor (to " + newCount + ")");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isTrackedArmorStack(Object stack) {
        synchronized (ARMOR_LIST_TO_ENTITY) {
            for (Map.Entry<List<?>, Integer> e : ARMOR_LIST_TO_ENTITY.entrySet()) {
                List<?> list = e.getKey();
                if (list == null || list.size() < 4) continue;
                for (int i = 0; i < 4; i++) {
                    if (list.get(i) == stack) return true;
                }
            }
        }
        return false;
    }

    public static String statusLine() {
        int lists;
        synchronized (ARMOR_LIST_TO_ENTITY) { lists = ARMOR_LIST_TO_ENTITY.size(); }
        return "tracking-active=" + TRACKING_ACTIVE
                + " protected-ids=" + PROTECTED_ENTITY_IDS.size()
                + " tracked-lists=" + lists;
    }

    public static boolean shouldBlockRawArmorListClear(Object list) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (list == null) return false;
            if (ARMOR_LIST_TO_ENTITY.get(list) == null) return false;
            List<?> l = asList(list);
            if (l == null) return false;
            for (int i = 0; i < Math.min(4, l.size()); i++) {
                if (isProtectedItemStack(l.get(i))) {
                    if (isCallerContainerClick()) return false;
                    AgentLog.log("[ArmorLockGuard] Blocked NonNullList.clear of protected armor list");
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockRawArmorListRemove(Object list, int index) {
        try {
            if (!TRACKING_ACTIVE) return false;
            if (RESTORING_ARMOR) return false;
            if (list == null || index < 0 || index > 3) return false;
            if (ARMOR_LIST_TO_ENTITY.get(list) == null) return false;
            List<?> l = asList(list);
            if (l == null || index >= l.size()) return false;
            Object stack = l.get(index);
            if (!isProtectedItemStack(stack)) return false;
            if (isCallerContainerClick()) return false;
            AgentLog.log("[ArmorLockGuard] Blocked NonNullList.remove of protected armor slot");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockSlotWrite(Object currentStack, Object newStack) {
        try {
            if (RESTORING_ARMOR) return false;
            if (!isProtectedItemStack(currentStack)) return false;

            // No-op writes (re-setting the same stack) are always fine.
            if (currentStack == newStack) return false;

            if (isCallerContainerClick()) return false;

            AgentLog.log("[ArmorLockGuard] Blocked non-container removal of protected armor piece");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldBlockDrop(Object itemStack) {
        boolean blocked = isProtectedItemStack(itemStack);
        if (blocked) {
            AgentLog.log("[ArmorLockGuard] Blocked world-drop of protected armor piece");
        }
        return blocked;
    }

    private static final String[] DEATH_PACKET_MARKERS = {"death", "deathscre", "die", "kill", "dead"};

    public static boolean shouldBlockPacket(Object packet) {
        return shouldBlockPacket(null, packet);
    }

    public static boolean shouldBlockPacket(Object listener, Object packet) {
        if (packet == null || PROTECTED_ENTITY_IDS.isEmpty()) return false;
        try {
            String simpleName = packet.getClass().getSimpleName();

            if (simpleName.equals("ClientboundRemoveEntitiesPacket")) {
                // getEntityIds() (SRG m_182730_) returns a fastutil IntList,
                // which IS a java.util.List — match by return type, not by the
                // mapped method name (Mojang names don't exist on the SRG runtime).
                for (Method m : packet.getClass().getMethods()) {
                    if (m.getParameterCount() == 0
                            && Iterable.class.isAssignableFrom(m.getReturnType())) {
                        Object result = m.invoke(packet);
                        if (result instanceof Iterable<?> ids) {
                            for (Object idObj : ids) {
                                if (idObj instanceof Integer id && PROTECTED_ENTITY_IDS.contains(id)) {
                                    AgentLog.log("[ArmorLockGuard] Blocked ClientboundRemoveEntitiesPacket for protected entity " + id);
                                    return true;
                                }
                            }
                        }
                        break;
                    }
                }
            } else if (simpleName.equals("ClientboundSetEntityDataPacket")
                    || simpleName.equals("ClientboundTakeItemEntityPacket")
                    || simpleName.equals("ClientboundEntityEventPacket")) {
                for (Method m : packet.getClass().getMethods()) {
                    if (m.getParameterCount() == 0
                            && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                        Object id = m.invoke(packet);
                        if (id instanceof Integer intId && PROTECTED_ENTITY_IDS.contains(intId)) {
                            AgentLog.log("[ArmorLockGuard] Blocked " + simpleName + " for protected entity " + intId);
                            return true;
                        }
                    }
                }
            }

            String className = packet.getClass().getName();
            if (className.startsWith("net.minecraft.")) return false;

            String lower = simpleName.toLowerCase();
            boolean nameMatch = false;
            for (String marker : DEATH_PACKET_MARKERS) {
                if (lower.contains(marker)) { nameMatch = true; break; }
            }
            if (!nameMatch) return false;

            Integer pid = listener != null ? protectedPlayerIdOf(listener) : null;
            if (pid != null && !PROTECTED_ENTITY_IDS.contains(pid)) return false;

            AgentLog.log("[ArmorLockGuard] Blocked fake-death packet " + className
                    + (pid != null ? " to protected player " + pid : " (recipient unknown)"));
            return true;
        } catch (Throwable t) {
        }
        return false;
    }

    private static Integer protectedPlayerIdOf(Object listener) {
        try {
            Object player = null;
            for (Method m : listener.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && (m.getName().equals("getPlayer") || m.getName().equals("m_142253_"))
                        && m.getReturnType().getName().contains("ServerPlayer")) {
                    player = m.invoke(listener);
                    break;
                }
            }
            if (player == null) {
                Class<?> c = listener.getClass();
                while (c != null && c != Object.class) {
                    for (Field f : c.getDeclaredFields()) {
                        if ((f.getName().equals("player") || f.getName().equals("f_9743_"))
                                && f.getType().getName().contains("ServerPlayer")) {
                            f.setAccessible(true);
                            player = f.get(listener);
                            break;
                        }
                    }
                    if (player != null) break;
                    c = c.getSuperclass();
                }
            }
            if (player == null) return null;
            int pid = entityId(player);
            return pid < 0 ? null : pid;
        } catch (Throwable t) {
            return null;
        }
    }
}