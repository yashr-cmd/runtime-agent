package runtime;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLauncherGuard {

    // Snapshot of the non-hostile transformation services: key -> decorator.
    private static volatile Map<Object, Object> servicesSnapshot;

    // Snapshot of the legitimate LaunchPluginHandler instance.
    private static volatile Object launchPluginsSnapshot;

    private ForgeLauncherGuard() {}

    public static void protect() {
        protectServices();
        protectLaunchPlugins();
    }

    private static void protectServices() {
        try {
            Class<?> launcherCls = Class.forName("cpw.mods.modlauncher.Launcher");
            Field instanceField = launcherCls.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object launcher = instanceField.get(null);
            if (launcher == null) return;

            Object tsHandler = getFieldValue(launcher, "transformationServicesHandler");
            if (tsHandler == null) return;

            Object raw = getFieldValue(tsHandler, "serviceLookup");
            if (!(raw instanceof Map)) return;
            Map<Object, Object> map = (Map<Object, Object>) raw;

            if (servicesSnapshot == null) {
                Map<Object, Object> snap = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : map.entrySet()) {
                    if (!isHostileService(e)) snap.put(e.getKey(), e.getValue());
                }
                servicesSnapshot = snap;
                System.err.println("[LauncherGuard] Snapshot: " + servicesSnapshot.size()
                        + " transformation services protected");
            }

            int purged = 0;
            for (Map.Entry<Object, Object> e : new LinkedHashMap<>(map).entrySet()) {
                if (isHostileService(e)) {
                    map.remove(e.getKey());
                    purged++;
                    System.err.println("[LauncherGuard] Purged hostile transformation service: "
                            + serviceName(e));
                }
            }

            int restored = 0;
            for (Map.Entry<Object, Object> e : servicesSnapshot.entrySet()) {
                if (!map.containsKey(e.getKey())) {
                    map.put(e.getKey(), e.getValue());
                    restored++;
                    System.err.println("[LauncherGuard] Restored transformation service removed by hostile mod: "
                            + serviceName(e));
                }
            }

            // Merge in legitimately new services so they get protected too, but
            // never drop our own from the snapshot.
            Map<Object, Object> merged = new LinkedHashMap<>(servicesSnapshot);
            boolean grew = false;
            for (Map.Entry<Object, Object> e : map.entrySet()) {
                if (!isHostileService(e) && merged.putIfAbsent(e.getKey(), e.getValue()) == null) {
                    grew = true;
                }
            }
            if (grew) servicesSnapshot = merged;

            if (purged > 0 || restored > 0) {
                System.err.println("[LauncherGuard] serviceLookup protect(): purged=" + purged + " restored=" + restored);
            }
        } catch (ClassNotFoundException e) {
            // ModLauncher not on classpath yet — too early, healer will retry.
        } catch (Throwable t) {
            System.err.println("[LauncherGuard] protectServices failed: " + t.getMessage());
        }
    }

    private static void protectLaunchPlugins() {
        try {
            Class<?> launcherCls = Class.forName("cpw.mods.modlauncher.Launcher");
            Object launcher = getFieldValue(launcherCls, "INSTANCE");
            if (launcher == null) return;

            Field f = launcherCls.getDeclaredField("launchPlugins");
            f.setAccessible(true);
            Object current = f.get(launcher);

            if (launchPluginsSnapshot == null) {
                launchPluginsSnapshot = current;
            } else if (current != launchPluginsSnapshot && current != null) {
                f.set(launcher, launchPluginsSnapshot);
                System.err.println("[LauncherGuard] Restored Launcher.launchPlugins after tampering ("
                        + current.getClass().getName() + " -> " + launchPluginsSnapshot.getClass().getName() + ")");
            }
        } catch (ClassNotFoundException e) {
            // not ready yet
        } catch (Throwable t) {
            System.err.println("[LauncherGuard] protectLaunchPlugins failed: " + t.getMessage());
        }
    }

    private static boolean isHostileService(Map.Entry<Object, Object> e) {
        Object svc = e.getValue();
        String name = svc == null ? String.valueOf(e.getKey()) : svc.getClass().getName();
        return HostileRegistry.isHostileClassName(name);
    }

    private static String serviceName(Map.Entry<Object, Object> e) {
        Object svc = e.getValue();
        if (svc != null) return svc.getClass().getName();
        return String.valueOf(e.getKey());
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}