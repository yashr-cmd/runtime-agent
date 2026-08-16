package runtime;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class LauncherStateGuard {
    private static final Object[] classNeedsTransformingBackup = new Object[1];
    private static final Object[] classLoaderBackup            = new Object[1];
    private static final Object[] serviceLookupBackup          = new Object[1];

    private static volatile boolean initialized = false;

    public static void snapshot() {
        try {
            Object launcher = getLauncher();
            if (launcher == null) return;

            Object transformStore = getTransformStore(launcher);
            if (transformStore != null) {
                Field f = getField(transformStore.getClass(), "classNeedsTransforming");
                if (f != null) {
                    Object val = f.get(transformStore);
                    if (val instanceof Set<?> set && !set.isEmpty()) {
                        classNeedsTransformingBackup[0] = new HashSet<>(set);
                    }
                }
            }

            Field clField = findField(launcher.getClass(), "classLoader");
            if (clField != null)
                classLoaderBackup[0] = clField.get(launcher); // classLoader is a single ref, no copy needed

            Object tsHandler = getTsHandler(launcher);
            if (tsHandler != null) {
                Field slField = findField(tsHandler.getClass(), "serviceLookup");
                if (slField != null) {
                    Object sl = slField.get(tsHandler);
                    if (sl instanceof Map<?,?> map && !map.isEmpty()) {
                        // Deep copy — pig2 removes entries from the original map
                        serviceLookupBackup[0] = new LinkedHashMap<>(map);
                    }
                }
            }

            initialized = true;
            System.out.println("[LauncherStateGuard] Snapshot taken (deep copies stored).");
        } catch (Throwable t) {
            System.err.println("[LauncherStateGuard] Snapshot failed: " + t.getMessage());
        }
    }

    public static void forceFullRestore() {
        try {
            Object launcher = getLauncher();
            if (launcher != null) {
                restoreClassNeedsTransforming(launcher);
                restoreClassLoader(launcher);
                restoreServiceLookup(launcher);
            }
        } catch (Throwable ignored) {}
    }

    public static void restore() {
        if (!initialized) return;
        try {
            Object launcher = getLauncher();
            if (launcher == null) return;
            restoreClassNeedsTransforming(launcher);
            restoreClassLoader(launcher);
            restoreServiceLookup(launcher);
        } catch (Throwable ignored) {}
    }

    private static void restoreClassNeedsTransforming(Object launcher) {
        try {
            if (classNeedsTransformingBackup[0] == null) return;
            Object transformStore = getTransformStore(launcher);
            if (transformStore == null) return;
            Field f = getField(transformStore.getClass(), "classNeedsTransforming");
            if (f == null) return;
            Object current = f.get(transformStore);
            // pig2 replaces it with a fresh empty HashSet — detect that
            if (current instanceof Set<?> set && set.isEmpty()) {
                // Restore a fresh deep copy so pig2 can't re-corrupt our stored backup
                Set<?> backup = (Set<?>) classNeedsTransformingBackup[0];
                f.set(transformStore, new HashSet<>(backup));
                System.out.println("[LauncherStateGuard] Restored classNeedsTransforming.");
            } else if (current instanceof Set<?> healthy && !healthy.isEmpty()) {
                // Still healthy — refresh our backup with a new deep copy
                classNeedsTransformingBackup[0] = new HashSet<>(healthy);
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreClassLoader(Object launcher) {
        try {
            if (classLoaderBackup[0] == null) return;
            Field f = findField(launcher.getClass(), "classLoader");
            if (f == null) return;
            Object current = f.get(launcher);
            if (current == null) return;
            // pig2 swaps it to its own backup (not a TransformingClassLoader)
            String name = current.getClass().getName();
            if (!name.contains("TransformingClassLoader")) {
                f.set(launcher, classLoaderBackup[0]);
                System.out.println("[LauncherStateGuard] Restored classLoader.");
            } else {
                classLoaderBackup[0] = current;
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void restoreServiceLookup(Object launcher) {
        try {
            if (serviceLookupBackup[0] == null) return;
            Object tsHandler = getTsHandler(launcher);
            if (tsHandler == null) return;
            Field slField = findField(tsHandler.getClass(), "serviceLookup");
            if (slField == null) return;
            Object current = slField.get(tsHandler);
            if (!(current instanceof Map<?,?> currentMap)) return;
            Map<?,?> backup = (Map<?,?>) serviceLookupBackup[0];

            if (currentMap.size() > backup.size()) {
                serviceLookupBackup[0] = new LinkedHashMap<>(currentMap);
                return;
            }

            if (currentMap.size() < backup.size()) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> mutable = (Map<Object, Object>) current;
                int restored = 0;
                for (Map.Entry<?,?> e : backup.entrySet()) {
                    if (!mutable.containsKey(e.getKey())) {
                        mutable.put(e.getKey(), e.getValue());
                        restored++;
                    }
                }
                if (restored > 0)
                    System.out.println("[LauncherStateGuard] Restored " + restored + " serviceLookup entries");
                // Refresh backup to match merged current
                serviceLookupBackup[0] = new LinkedHashMap<>(mutable);
            }
        } catch (Throwable ignored) {}
    }

    private static Object getLauncher() {
        try {
            Class<?> cls = Class.forName("cpw.mods.modlauncher.Launcher");
            Field f = cls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) { return null; }
    }

    private static Object getTsHandler(Object launcher) {
        try {
            Field f = findField(launcher.getClass(), "transformationServicesHandler");
            if (f == null) return null;
            return f.get(launcher);
        } catch (Throwable t) { return null; }
    }

    private static Object getTransformStore(Object launcher) {
        try {
            Object tsHandler = getTsHandler(launcher);
            if (tsHandler == null) return null;
            Field f = findField(tsHandler.getClass(), "transformStore");
            if (f == null) return null;
            return f.get(tsHandler);
        } catch (Throwable t) { return null; }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Field getField(Class<?> cls, String name) {
        return findField(cls, name);
    }
}