package runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defends the Forge event bus against pig2's two-pronged runtime attack:
 *
 *  1. unregisterEventSubscriptionByOtherBadMOD() — pig2 reflects into
 *     EventBus.listeners (ConcurrentHashMap<Object, List<IEventListener>>)
 *     and removes entries whose key class is not a pig2 class.
 *
 *  2. registerForgeEventBus() / preventForgeEventAttack() — pig2 keeps a
 *     backup reference (gForgeEventBusBackup) to the real IEventBus and
 *     re-registers itself on a timer if it detects it was removed.
 *
 * Our defence:
 *  - On first call (snapshotAndPurge) we grab the same `listeners` map from
 *    MinecraftForge.EVENT_BUS and snapshot all non-pig2 entries.
 *  - restore() runs every 30 s from the healer loop: re-inserts any entries
 *    that pig2 deleted, and removes any pig2-owned entries that snuck back in.
 *  - We also null out the gForgeEventBusBackup field on MyHelper so pig2's
 *    periodic re-register call targets null and silently crashes instead of
 *    restoring its listener registrations.
 */
public class EventBusFixer {

    // Snapshot of legitimate (non-pig2) listener entries: key -> listener list copy
    private static final Map<Object, List<Object>> goodListenersSnapshot = new ConcurrentHashMap<>();

    // Cached field refs so we don't re-reflect on every restore() call
    private static volatile Field  listenersField    = null; // EventBus.listeners
    private static volatile Object eventBusInstance  = null; // MinecraftForge.EVENT_BUS value
    private static volatile boolean initialized      = false;

    // -----------------------------------------------------------------------
    // Called once from agentmainImpl, after the initial pig2 retransform.
    // It's OK if Forge isn't on the classpath yet — snapshotAndPurge returns
    // silently and restore() will retry on the next healer cycle.
    // -----------------------------------------------------------------------
    public static void snapshotAndPurge() {
        try {
            if (!resolveEventBus()) return;
            Map<Object, List<Object>> listeners = getListenersMap();
            if (listeners == null) return;

            // Snapshot all non-pig2 entries; remove pig2 entries immediately.
            synchronized (goodListenersSnapshot) {
                for (var entry : new ArrayList<>(listeners.entrySet())) {
                    if (isPig2Key(entry.getKey())) {
                        listeners.remove(entry.getKey());
                        System.err.println("[EventBusNullifier] Purged pig2 listener entry: "
                                + entry.getKey().getClass().getName());
                    } else {
                        // Deep-copy the list so pig2 can't corrupt our snapshot by mutating it
                        goodListenersSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                    }
                }
            }

            nullifyPig2EventBusBackup();

            initialized = true;
            System.err.println("[EventBusNullifier] Snapshot taken: "
                    + goodListenersSnapshot.size() + " legitimate listener entries protected.");

        } catch (Throwable t) {
            System.err.println("[EventBusNullifier] snapshotAndPurge failed (Forge not ready?): " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Called every 30 s from the healer loop.
    // -----------------------------------------------------------------------
    public static void restore() {
        try {
            if (!initialized) {
                // Forge wasn't ready during snapshotAndPurge — retry now
                snapshotAndPurge();
                return;
            }

            Map<Object, List<Object>> listeners = getListenersMap();
            if (listeners == null) return;

            int restored = 0;
            int purged   = 0;

            synchronized (goodListenersSnapshot) {
                // 1. Remove any pig2 entries that sneaked back in
                for (Object key : new ArrayList<>(listeners.keySet())) {
                    if (isPig2Key(key)) {
                        listeners.remove(key);
                        purged++;
                        System.err.println("[EventBusNullifier] Re-purged pig2 listener: "
                                + key.getClass().getName());
                    }
                }

                // 2. Re-insert any legitimate entries pig2 deleted
                for (var snap : goodListenersSnapshot.entrySet()) {
                    if (!listeners.containsKey(snap.getKey())) {
                        listeners.put(snap.getKey(), new ArrayList<>(snap.getValue()));
                        restored++;
                        System.err.println("[EventBusNullifier] Restored listener entry: "
                                + snap.getKey().getClass().getName());
                    } else {
                        // Entry present — refresh snapshot if new listeners registered legitimately
                        List<Object> current = listeners.get(snap.getKey());
                        if (current != null && current.size() > snap.getValue().size()) {
                            goodListenersSnapshot.put(snap.getKey(), new ArrayList<>(current));
                        }
                    }
                }
            }

            // 3. Null out pig2's backup reference every cycle so re-register calls die quietly
            nullifyPig2EventBusBackup();

            if (restored > 0 || purged > 0) {
                System.err.println("[EventBusNullifier] restore(): purged=" + purged + " restored=" + restored);
            }

        } catch (Throwable t) {
            System.err.println("[EventBusNullifier] restore() error: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Resolve MinecraftForge.EVENT_BUS and cache its `listeners` field.
    // Returns false if Forge classes aren't loaded yet.
    // -----------------------------------------------------------------------
    private static boolean resolveEventBus() {
        if (eventBusInstance != null && listenersField != null) return true;
        try {
            Class<?> forgeCls = Class.forName("net.minecraftforge.common.MinecraftForge");
            Field busFld = forgeCls.getDeclaredField("EVENT_BUS");
            busFld.setAccessible(true);
            eventBusInstance = busFld.get(null);
            if (eventBusInstance == null) return false;

            // net.minecraftforge.eventbus.EventBus — walk hierarchy for `listeners`
            listenersField = findField(eventBusInstance.getClass(), "listeners");
            return listenersField != null;
        } catch (ClassNotFoundException e) {
            return false; // Forge not on classpath yet — silent fail
        } catch (Throwable t) {
            System.err.println("[EventBusNullifier] resolveEventBus failed: " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, List<Object>> getListenersMap() {
        try {
            Object raw = listenersField.get(eventBusInstance);
            if (!(raw instanceof Map)) return null;
            return (Map<Object, List<Object>>) raw;
        } catch (Throwable t) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Returns true if the listener-map key belongs to pig2.
    // The key is either the registered object itself or a Class<?> token;
    // we check the class name either way.
    // -----------------------------------------------------------------------
    private static boolean isPig2Key(Object key) {
        if (key == null) return false;
        String name = (key instanceof Class<?>)
                ? ((Class<?>) key).getName()
                : key.getClass().getName();
        return name.startsWith("kakiku.") || name.contains("pig2mod") || name.contains("Pig2");
    }

    // -----------------------------------------------------------------------
    // Nullifies MyHelper.gForgeEventBusBackup so pig2's periodic
    // preventForgeEventAttack() lambda finds null and dies silently
    // instead of re-registering pig2 on the event bus.
    // -----------------------------------------------------------------------
    private static void nullifyPig2EventBusBackup() {
        try {
            Class<?> myHelper = Class.forName("kakiku.pig2mod.MyHelper");
            Field backupField = findField(myHelper, "gForgeEventBusBackup");
            if (backupField == null) return;
            Object current = backupField.get(null);
            if (current != null) {
                backupField.set(null, null);
                System.err.println("[EventBusNullifier] Nullified MyHelper.gForgeEventBusBackup");
            }
        } catch (ClassNotFoundException e) {
            // pig2 not loaded — nothing to do
        } catch (Throwable t) {
            System.err.println("[EventBusNullifier] nullifyPig2EventBusBackup failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Walk superclass chain to find a field and make it accessible.
    // -----------------------------------------------------------------------
    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
