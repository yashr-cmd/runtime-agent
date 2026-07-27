package runtime;

import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class RuntimeAgent {

    private static volatile boolean initialized = false;
    private static volatile GodTransformer godTransformer;

    private static final Object[] INST = new Object[1];

    private static final Set<String> FAILED_CLASSES =
            ConcurrentHashMap.newKeySet();

    private static final Set<String> PATCHED_MC_CLASSES =
            ConcurrentHashMap.newKeySet();

    static final String[] PIG2_PACKAGES = {
            "kakiku/pig2mod/",
            "kakiku/"
    };

    // --- Pig2 dirty-detection state ---
    // Cache of known pig2 classes discovered so far (populated lazily by GodTransformer + one-time scan)
    private static final Set<Class<?>> pig2ClassCache = ConcurrentHashMap.newKeySet();
    private static volatile boolean pig2CacheBuilt = false;

    // Maps className -> CRC32 of the raw bytecode we last saw BEFORE we patched it.
    // If the CRC changes on the next check, pig2 rewrote itself -> retransform.
    private static final Map<String, Long> pig2BytecodeChecksums = new ConcurrentHashMap<>();

    public static void agentmain(String args, Instrumentation inst) {
        System.setProperty("transfinity.agent.loaded", "true");
        if (initialized) {
            System.err.println("[Transfinity Runtime] agentmain was called again, skipping.");
            return;
        }
        initialized = true;
        try {
            agentmainImpl(args, inst);
        } catch (Throwable t) {
            System.err.println("[Transfinity Runtime] FATAL: agentmain threw — agent partially loaded");
            t.printStackTrace(System.err);
        }
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    private static void agentmainImpl(String args, Instrumentation inst) {
        INST[0] = inst;
        System.err.println("[Transfinity Runtime] agentmain started");

        LauncherStateGuard.snapshot();

        godTransformer = new GodTransformer();
        inst.addTransformer(godTransformer, true);

        System.err.println("[Transfinity Runtime] Patching hostile transformers...");
        // Initial scan: build the pig2 cache and do the first retransform
        retransformPig2Classes(inst, true);

        stripPig2FromForgeTransformers();
        ThreadSanitizer.killAll();  // kill any hostile timer threads already spawned before we loaded
        EventBusFixer.snapshotAndPurge();
        startLuaEngine();

        Thread watchdog = TransformerWatchdog.startAndReturn(inst, godTransformer);
        Thread healer = new Thread(() -> selfHealingLoop(), "Server-Worker-1");
        healer.setDaemon(true);
        healer.start();

        System.err.println("[Transfinity Runtime]: Agent Attached.");
    }

    @SuppressWarnings("unchecked")
    private static void stripPig2FromForgeTransformers() {
        try {
            Class<?> launcherCls = Class.forName("cpw.mods.modlauncher.Launcher");
            Field instanceField = launcherCls.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object launcher = instanceField.get(null);
            if (launcher == null) return;

            Object tsHandler = getFieldValue(launcher, "transformationServicesHandler");
            if (tsHandler == null) return;

            Object serviceLookup = getFieldValue(tsHandler, "serviceLookup");
            if (!(serviceLookup instanceof Map)) return;

            Map<Object, Object> map = (Map<Object, Object>) serviceLookup;
            int before = map.size();
            map.entrySet().removeIf(entry -> {
                String key = String.valueOf(entry.getKey());
                if (key.contains("ti_bootstrap") || key.contains("ti_coremod")
                        || key.contains("ti_early") || key.contains("transfinity")) return false;
                return key.contains("pig2") || key.contains("kakiku") ||
                        key.contains("Pig2") || key.contains("Kakiku");
            });
            int removed = before - map.size();
            if (removed > 0)
                System.err.println("[Transfinity Runtime] Stripped " + removed + " pig2 transformer(s) from Forge");
        } catch (ClassNotFoundException e) {
            // ModLauncher not on classpath yet — too early, watchdog will retry
        } catch (Throwable t) {
            System.err.println("[Transfinity] stripPig2FromForgeTransformers failed: " + t.getMessage());
        }
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

    private static void startLuaEngine() {
        try {
            Class<?> engineClass = Class.forName("runtime.LuaArmorEngine");
            engineClass.getMethod("start").invoke(null);
        } catch (Throwable t) {
            System.err.println("[Transfinity Runtime]   LUA FAILED: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // CRC32 helper — fast, zero allocation, no crypto overhead
    // ---------------------------------------------------------------------------
    private static long crc32(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    // ---------------------------------------------------------------------------
    // Called by GodTransformer after it patches a pig2 class.
    // Stores the CRC of the bytes pig2 handed us (pre-patch) so we can detect
    // if pig2 rewrites itself later. Also registers the class in the cache.
    // ---------------------------------------------------------------------------
    static void registerPig2Class(Class<?> c, byte[] rawBytesBeforePatch) {
        if (c == null || rawBytesBeforePatch == null) return;
        pig2ClassCache.add(c);
        // Always overwrite — this is called on every transform, so it stays current
        pig2BytecodeChecksums.put(c.getName(), crc32(rawBytesBeforePatch));
    }

    // ---------------------------------------------------------------------------
    // Dirty-detection: compares the stored pre-patch CRC against what pig2's
    // classloader currently has on disk/in memory.
    // NO retransform triggered here — we read bytes directly via the classloader
    // to avoid accidentally going through GodTransformer just to do a check.
    // ---------------------------------------------------------------------------
    private static boolean isPig2Dirty(Class<?> c) {
        if (FAILED_CLASSES.contains(c.getName())) return false;
        Long knownCrc = pig2BytecodeChecksums.get(c.getName());
        if (knownCrc == null) return true; // never seen -> treat as dirty

        // Read raw bytecode straight from the classloader — no retransform involved
        String resourcePath = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        if (cl == null) return false; // bootstrap class, pig2 wouldn't be here

        byte[] currentBytes;
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) return false; // can't read -> assume clean
            currentBytes = is.readAllBytes();
        } catch (Throwable t) {
            return false;
        }

        long currentCrc = crc32(currentBytes);
        boolean dirty = currentCrc != knownCrc;
        if (dirty) {
            System.err.println("[Transfinity Dirty] pig2 class " + c.getName()
                    + " changed! CRC " + Long.toHexString(knownCrc)
                    + " -> " + Long.toHexString(currentCrc) + " — retransforming");
            pig2BytecodeChecksums.put(c.getName(), currentCrc);
        }
        return dirty;
    }

    // ---------------------------------------------------------------------------
    // Main pig2 retransform entry point.
    //   forceScan=true  -> walk ALL loaded classes (startup / resurrection only)
    //   forceScan=false -> use cached set + dirty-check only (periodic calls)
    // ---------------------------------------------------------------------------
    static int retransformPig2Classes(Instrumentation inst, boolean forceScan) {
        // On first call or forced scan: rebuild cache from all loaded classes
        if (forceScan || !pig2CacheBuilt) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String cn = c.getName().replace('.', '/');
                boolean isPig2 = false;
                for (String pkg : PIG2_PACKAGES) {
                    if (cn.startsWith(pkg)) { isPig2 = true; break; }
                }
                if (!isPig2 || !inst.isModifiableClass(c) || FAILED_CLASSES.contains(c.getName())) continue;
                pig2ClassCache.add(c);
                // Seed CRC as 0 so first dirty check always fires a retransform
                pig2BytecodeChecksums.putIfAbsent(c.getName(), 0L);
            }
            pig2CacheBuilt = true;
        }

        if (pig2ClassCache.isEmpty()) return 0;

        int count = 0;
        for (Class<?> c : pig2ClassCache) {
            if (FAILED_CLASSES.contains(c.getName())) continue;
            if (!inst.isModifiableClass(c)) continue;

            // DIRTY CHECK — skip if bytecode hasn't changed
            if (!forceScan && !isPig2Dirty(c)) continue;

            try {
                ClassLoader cl = c.getClassLoader();
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                inst.retransformClasses(c);
                count++;
            } catch (VerifyError ve) {
                FAILED_CLASSES.add(c.getName());
                pig2ClassCache.remove(c);
                System.err.println("[Transfinity Runtime] Permanent VerifyError neutralizing hostile mod class "
                        + c.getName() + " — blacklisted");
            } catch (Throwable t) {
                System.err.println("[Transfinity Runtime] Failed patch of "
                        + c.getName() + ": " + t.getMessage());
            }
        }
        if (count > 0)
            System.err.println("[Transfinity Runtime] hostile mod patch: " + count + " classes patched");
        return count;
    }

    // Backwards-compat overload used by watchdog resurrection path (always force)
    static int retransformPig2Classes(Instrumentation inst) {
        return retransformPig2Classes(inst, true);
    }

    static void retransformImportantClasses(Instrumentation inst) {
        String[] targets = {
                "net/minecraft/world/entity/LivingEntity",
                "net/minecraft/world/entity/Entity",
                "net/minecraft/network/syncher/SynchedEntityData",
                "net/minecraft/server/level/ServerPlayer",
                "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback"
        };
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String className = c.getName().replace('.', '/');
            boolean matches = false;
            for (String t : targets) { if (className.equals(t)) { matches = true; break; } }
            if (!matches || !inst.isModifiableClass(c)) continue;
            if (FAILED_CLASSES.contains(c.getName())) continue;
            try {
                ClassLoader cl = c.getClassLoader();
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                TransformerWatchdog.retransformStealth(inst, c);
            } catch (VerifyError ve) {
                FAILED_CLASSES.add(c.getName());
                System.err.println("[Transfinity Runtime] Permanent VerifyError on " + c.getName() + " - blacklisted");
            } catch (Throwable t) {
                System.err.println("[Transfinity Runtime] FAILED to retransform " + c.getName());
            }
        }
    }

    private static void selfHealingLoop() {
        Instrumentation inst = (Instrumentation) INST[0];
        int iteration = 0;
        while (true) {
            try {
                // Slowed down — healer's pig2 work is now cheap (dirty-check only)
                Thread.sleep(500);
                iteration++;

                LauncherStateGuard.restore();

                // Dirty-check only sweep every ~30 seconds (60 iters * 500ms)
                // Costs almost nothing when pig2 hasn't changed — just a classloader read + CRC per class
                if (iteration % 60 == 0) {
                    retransformPig2Classes(inst, false);
                    stripPig2FromForgeTransformers();
                    ThreadSanitizer.killAll();
                    EventBusFixer.restore();
                }

                String signal = System.getProperty("transfinity.transformer.needs_resurrection");
                if (signal != null) {
                    System.clearProperty("transfinity.transformer.needs_resurrection");
                    if (godTransformer != null) {
                        try {
                            inst.addTransformer(godTransformer, true);
                            // Resurrection -> force full scan since we were blind while dead
                            retransformPig2Classes(inst, true);
                            System.err.println("[Transfinity Healer] GodTransformer Healed + full retransform done");
                        } catch (Exception e) {
                            System.err.println("[Transfinity Healer] Healing failed: " + e.getMessage());
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {}
        }
    }

    public static class GodTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain domain,
                                byte[] classfileBuffer) {
            if (className == null) return null;
            try {
                String cn = className.replace('.', '/');

                for (String pkg : PIG2_PACKAGES) {
                    if (cn.startsWith(pkg)) {
                        // Record CRC of the raw bytes BEFORE we patch, then add to cache.
                        // This is the ground-truth we'll diff against in dirty checks.
                        if (classBeingRedefined != null) {
                            registerPig2Class(classBeingRedefined, classfileBuffer);
                        }
                        return RuntimePatch.patchPig2Class(classfileBuffer, cn);
                    }
                }

        if (classBeingRedefined != null) {
                    if (cn.equals("net/minecraft/world/entity/LivingEntity"))
                        return RuntimePatch.patchLivingEntity(classfileBuffer);
                    if (cn.equals("net/minecraft/world/entity/Entity"))
                        return RuntimePatch.patchEntity(classfileBuffer);
                    if (cn.equals("net/minecraft/network/syncher/SynchedEntityData"))
                        return RuntimePatch.patchSynchedEntityData(classfileBuffer);
                    if (cn.equals("net/minecraft/server/level/ServerPlayer"))
                        return RuntimePatch.patchServerPlayer(classfileBuffer);
                    if (cn.equals("net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback"))
                        return RuntimePatch.patchEntityCallback(classfileBuffer);
                    if (cn.equals("net/minecraft/server/network/ServerGamePacketListenerImpl"))
                        return RuntimePatch.patchServerGamePacketListener(classfileBuffer);
                }
                return null;
            } catch (Exception e) {
                System.err.println("[GodTransformer] Error transforming " + className);
                return null;
            }
        }
    }
}
