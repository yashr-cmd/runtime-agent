package runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HostileRegistry {

    // Registered hostile package prefixes (both dotted and internal slash forms).
    private static final Set<String> HOSTILE_PREFIXES = ConcurrentHashMap.newKeySet();

    // Registered hostile exact class names (dotted).
    private static final Set<String> HOSTILE_EXACT = ConcurrentHashMap.newKeySet();

    // Known hostile name fragments matched as substrings (keeps the legacy
    // EventBusFixer/ThreadSanitizer behaviour for the pig2 family even when its
    // classes are split across odd packages).
    private static final Set<String> HOSTILE_FRAGMENTS = ConcurrentHashMap.newKeySet();

    // Class-name prefixes that are framework/infra or ours — never treated as
    // the "actor" of an attack even if they appear next to an attack signature.
    private static final String[] SAFE_FRAMES = {
            "java.", "javax.", "jdk.", "jdk.internal.", "sun.", "com.sun.",
            "runtime.",                 // this agent: watchdog, healer, guards, helpers
            "net.minecraft.", "net.minecraftforge.",
            "cpw.mods.",                // ModLauncher infrastructure itself
            "org.slf4j.", "org.apache.logging.", "com.google.", "org.objectweb.",
            "com.mojang.", "io.netty.", "it.unimi.", "org.lwjgl."
    };

    static {
        HOSTILE_FRAGMENTS.add("pig2mod");
        HOSTILE_FRAGMENTS.add("kakiku");
        String seeds = System.getProperty("transfinity.hostile", "kakiku,pig2mod");
        for (String s : seeds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) addPrefix(t);
        }
    }

    private HostileRegistry() {}

    public static void addPrefix(String pkgPrefix) {
        if (pkgPrefix == null) return;
        String t = pkgPrefix.trim();
        if (t.isEmpty()) return;
        HOSTILE_PREFIXES.add(t);
        HOSTILE_PREFIXES.add(t.replace('.', '/'));
        HOSTILE_PREFIXES.add(t.replace('/', '.'));
    }

    public static boolean markHostile(String className) {
        if (className == null) return false;
        String dotted = className.replace('/', '.');
        if (isHostileClassName(dotted)) return false;
        HOSTILE_EXACT.add(dotted);
        int idx = dotted.lastIndexOf('.');
        if (idx > 0) addPrefix(dotted.substring(0, idx));
        return true;
    }

    public static boolean isHostileClassName(String name) {
        if (name == null) return false;
        String dotted = name.replace('/', '.');
        if (HOSTILE_EXACT.contains(dotted)) return true;
        for (String p : HOSTILE_PREFIXES) {
            if (dotted.startsWith(p)) return true;
        }
        for (String frag : HOSTILE_FRAGMENTS) {
            if (dotted.contains(frag)) return true;
        }
        return false;
    }

    public static boolean isHostileClass(Class<?> c) {
        return c != null && isHostileClassName(c.getName());
    }

    public static String[] snapshotPrefixes() {
        List<String> out = new ArrayList<>();
        for (String p : HOSTILE_PREFIXES) {
            String slash = p.replace('.', '/');
            if (slash.indexOf('/') >= 0 && !out.contains(slash)) out.add(slash);
        }
        return out.toArray(new String[0]);
    }

    public static boolean isCallerHostile() {
        return isHostileStack(Thread.currentThread().getStackTrace());
    }

    private static boolean isHostileStack(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (isHostileClassName(f.getClassName())) return true;
        }
        return false;
    }

    public static boolean isHostileThread(Thread t) {
        try {
            return isHostileStack(t.getStackTrace());
        } catch (Throwable ignored) {
            return false;
        }
    }
    /**
     * Scans every live thread. Kills threads that are (a) registered-hostile,
     * or (b) currently executing a transformer-kill signature (removeTransformer
     * call, or direct sun.instrument internals poking) — in the latter case the
     * responsible non-framework classes are marked hostile first.
     *
     * @return the number of NEWLY marked hostile classes (so the caller can
     *         trigger a forced retransform rescan).
     */
    public static int scanAndKill() {
        int newlyMarked = 0;
        for (Thread t : getAllThreads()) {
            if (t == Thread.currentThread()) continue;
            if (!t.isAlive()) continue;

            boolean hostile = isHostileThread(t);
            List<String> actors = findAttackActors(t);
            if (!actors.isEmpty()) {
                for (String actor : actors) {
                    if (markHostile(actor)) {
                        newlyMarked++;
                        System.err.println("[HostileRegistry] BEHAVIOR detected: marked " + actor
                                + " hostile for transformer-kill attempt on thread " + t.getName());
                    }
                }
                hostile = true;
            }

            if (hostile) {
                killThread(t);
            }
        }
        return newlyMarked;
    }

    /**
     * Finds the non-framework "actor" classes on a thread's stack whose presence
     * is adjacent to a transformer-kill signature. Empty list = no signature.
     */
    private static List<String> findAttackActors(Thread t) {
        List<String> actors = new ArrayList<>();
        try {
            StackTraceElement[] stack = t.getStackTrace();
            boolean signature = false;
            for (StackTraceElement f : stack) {
                if (isTransformAttackFrame(f.getClassName(), f.getMethodName())) {
                    signature = true;
                    break;
                }
            }
            if (!signature) return actors;

            for (StackTraceElement f : stack) {
                String cls = f.getClassName();
                if (isSafeFrame(cls)) continue;
                String actor = cls;
                if (!actors.contains(actor)) actors.add(actor);
            }
        } catch (Throwable ignored) {}
        return actors;
    }

    private static boolean isTransformAttackFrame(String className, String methodName) {
        if (className == null) return false;
        // Instrumentation.removeTransformer(ClassFileTransformer) — the public API
        // a mod must call to un-register somebody else's transformer.
        if ("removeTransformer".equals(methodName)) return true;
        // Direct access to the JVM's transformer-list internals (pig2's Unsafe path).
        return className.startsWith("sun.instrument.")
                && (className.contains("TransformerManager") || className.contains("TransformerInfo"));
    }

    private static boolean isSafeFrame(String cls) {
        for (String p : SAFE_FRAMES) {
            if (cls.startsWith(p)) return true;
        }
        return false;
    }

    private static void killThread(Thread t) {
        try {
            t.interrupt();
            t.join(50);
            if (t.isAlive()) {
                //noinspection deprecation
                t.stop();
            }
            System.err.println("[HostileRegistry] Killed hostile thread: "
                    + t.getName() + " (#" + t.getId() + ")");
        } catch (Throwable e) {
            System.err.println("[HostileRegistry] Failed to kill thread "
                    + t.getName() + ": " + e.getMessage());
        }
    }

    private static Thread[] getAllThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] threads = new Thread[root.activeCount() + 64];
        int count = root.enumerate(threads, true);
        Thread[] result = new Thread[count];
        System.arraycopy(threads, 0, result, 0, count);
        return result;
    }
}