package runtime;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.jar.JarFile;

public class RuntimeAgent {

    private static volatile boolean initialized = false;
    private static volatile GodTransformer godTransformer;

    private static final Object[] INST = new Object[1];

    private static final Set<String> FAILED_CLASSES =
            ConcurrentHashMap.newKeySet();

    private static final Set<String> PATCHED_MC_CLASSES =
            ConcurrentHashMap.newKeySet();

    static final String[] PROTECTED_MC_CLASSES = {
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/player/Player",
            "net/minecraft/world/entity/player/Inventory",
            "net/minecraft/world/item/ItemStack",
            "net/minecraft/network/syncher/SynchedEntityData",
            "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback",
            "net/minecraft/server/network/ServerGamePacketListenerImpl",
            "net/minecraft/world/ContainerHelper",
            "net/minecraft/core/NonNullList",
            "net/minecraft/core/MappedRegistry"
    };

    private static final Set<Class<?>> hostileClassCache = ConcurrentHashMap.newKeySet();
    private static volatile boolean hostileCacheBuilt = false;
    private static final Map<String, Long> hostileBytecodeChecksums = new ConcurrentHashMap<>();

    public static void agentmain(String args, Instrumentation inst) {
        System.setProperty("transfinity.agent.loaded", "true");
        if (initialized) {
            AgentLog.log("[Transfinity Runtime] agentmain was called again, skipping.");
            return;
        }
        initialized = true;
        try {
            if (runtime.bootstrap.NativeBootstrap.ensureAsync()) {
                return;
            }
            agentmainImpl(args, inst);
        } catch (Throwable t) {
            AgentLog.logThrowable("[Transfinity Runtime] FATAL: agentmain threw — agent partially loaded", t);
        }
    }

    public static void premain(String args, Instrumentation inst) {
        AgentLog.log("[Transfinity Runtime] premain started");
        if (runtime.bootstrap.NativeBootstrap.ensure()) {
            return;
        }
        agentmain(args, inst);
    }

    private static void agentmainImpl(String args, Instrumentation inst) {
        INST[0] = inst;
        AgentLog.log("[Transfinity Runtime] agentmain started");

        exposeRuntimeClassesToBootstrap(inst);

        Thread startupThread = new HardenedThread(RuntimeAgent::gatedStartup, "TI-Coexistence-Gate");
        startupThread.setDaemon(true);
        startupThread.start();

        AgentLog.log("[Transfinity Runtime]: Agent Attached (startup continuing async).");
    }

    private static void gatedStartup() {
        Instrumentation inst = (Instrumentation) INST[0];
        waitForCoexistenceSettled(inst);

        LauncherStateGuard.snapshot();

        godTransformer = new GodTransformer();
        inst.addTransformer(godTransformer, true);

        AgentLog.log("[Transfinity Runtime] Patching hostile transformers...");
        // Initial scan: build the hostile-class cache and do the first retransform
        retransformHostileClasses(inst, true);

        retransformImportantClasses(inst);

        ForgeLauncherGuard.protect();
        ThreadSanitizer.killAll();  // kill any hostile timer threads already spawned before we loaded
        EventBusFixer.snapshotAndPurge();
        startLuaEngine();

        Thread watchdog = TransformerWatchdog.startAndReturn(inst, godTransformer);
        Thread healer = new HardenedThread(() -> selfHealingLoop(), "Server-Worker-1");
        healer.setDaemon(true);
        healer.start();

        AgentLog.log("[Transfinity Runtime]: Gated startup complete.");
    }

    private static void exposeRuntimeClassesToBootstrap(Instrumentation inst) {
        try {
            File self = new File(
                    RuntimeAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.isFile() && self.getName().endsWith(".jar")) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
                AgentLog.log("[Transfinity Runtime] Exposed " + self.getName()
                        + " to bootstrap classloader search — runtime.* classes now resolvable from any layer");
            } else {
                AgentLog.log("[Transfinity Runtime] WARNING: agent not running from a jar ("
                        + self + ") — skipping bootstrap classloader exposure. runtime.* helper classes"
                        + " (HealthGuard, ArmorLockGuard, etc) will NOT resolve inside Forge's module layers"
                        + " and patched MC classes will throw NoClassDefFoundError.");
            }
        } catch (Throwable t) {
            AgentLog.logThrowable("[Transfinity Runtime] FAILED to expose runtime classes to bootstrap classloader", t);
        }
    }

    private static void waitForCoexistenceSettled(Instrumentation inst) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if ("true".equals(System.getProperty("transfinity.coexistence.done"))) {
                AgentLog.log("[Transfinity Runtime] coexistence settled, proceeding");
                return;
            }
            if (entityBootstrapDone(inst)) {
                AgentLog.log("[Transfinity Runtime] Entity/LivingEntity already loaded by FML bootstrap "
                        + "- initial-load race window has passed, proceeding");
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        AgentLog.log("[Transfinity Runtime] WARNING: neither coexistence flag nor entity bootstrap "
                + "seen within 5s, proceeding anyway");
    }

    private static boolean entityBootstrapDone(Instrumentation inst) {
        boolean sawEntity = false, sawLivingEntity = false;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals("net.minecraft.world.entity.Entity")) sawEntity = true;
            else if (n.equals("net.minecraft.world.entity.LivingEntity")) sawLivingEntity = true;
            if (sawEntity && sawLivingEntity) return true;
        }
        return false;
    }

    private static void startLuaEngine() {
        try {
            Class<?> engineClass = Class.forName("runtime.LuaArmorEngine");
            engineClass.getMethod("start").invoke(null);
        } catch (Throwable t) {
            AgentLog.logThrowable("[Transfinity Runtime]   LUA FAILED", t);
        }
    }

    private static long crc32(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    static void registerHostileClass(Class<?> c, byte[] rawBytesBeforePatch) {
        if (c == null || rawBytesBeforePatch == null) return;
        hostileClassCache.add(c);
        // Always overwrite — this is called on every transform, so it stays current
        hostileBytecodeChecksums.put(c.getName(), crc32(rawBytesBeforePatch));
    }

    private static boolean isHostileDirty(Class<?> c) {
        if (FAILED_CLASSES.contains(c.getName())) return false;
        Long knownCrc = hostileBytecodeChecksums.get(c.getName());
        if (knownCrc == null) return true; // never seen -> treat as dirty

        // Read raw bytecode straight from the classloader — no retransform involved
        String resourcePath = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        if (cl == null) return false; // bootstrap class, hostile mod wouldn't be here

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
            AgentLog.log("[Transfinity Dirty] hostile class " + c.getName()
                    + " changed! CRC " + Long.toHexString(knownCrc)
                    + " -> " + Long.toHexString(currentCrc) + " — retransforming");
            hostileBytecodeChecksums.put(c.getName(), currentCrc);
        }
        return dirty;
    }

    static int retransformHostileClasses(Instrumentation inst, boolean forceScan) {
        if (forceScan || !hostileCacheBuilt) {
            String[] prefixes = HostileRegistry.snapshotPrefixes();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String cn = c.getName().replace('.', '/');
                boolean hostile = HostileRegistry.isHostileClassName(cn);
                if (!hostile) {
                    for (String pkg : prefixes) {
                        if (cn.startsWith(pkg)) { hostile = true; break; }
                    }
                }
                if (!hostile || !inst.isModifiableClass(c) || FAILED_CLASSES.contains(c.getName())) continue;
                hostileClassCache.add(c);
                // Seed CRC as 0 so first dirty check always fires a retransform
                hostileBytecodeChecksums.putIfAbsent(c.getName(), 0L);
            }
            hostileCacheBuilt = true;
        }

        if (hostileClassCache.isEmpty()) return 0;

        int count = 0;
        for (Class<?> c : hostileClassCache) {
            if (FAILED_CLASSES.contains(c.getName())) continue;
            if (!inst.isModifiableClass(c)) continue;

            // DIRTY CHECK — skip if bytecode hasn't changed
            if (!forceScan && !isHostileDirty(c)) continue;

            try {
                ClassLoader cl = c.getClassLoader();
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                inst.retransformClasses(c);
                count++;
            } catch (VerifyError ve) {
                FAILED_CLASSES.add(c.getName());
                hostileClassCache.remove(c);
                AgentLog.log("[Transfinity Runtime] Permanent VerifyError neutralizing hostile mod class "
                        + c.getName() + " — blacklisted");
            } catch (Throwable t) {
                AgentLog.logThrowable("[Transfinity Runtime] Failed patch of " + c.getName(), t);
            }
        }
        if (count > 0)
            AgentLog.log("[Transfinity Runtime] hostile mod patch: " + count + " classes patched");
        return count;
    }

    // Backwards-compat overload used by watchdog resurrection path (always force)
    static int retransformHostileClasses(Instrumentation inst) {
        return retransformHostileClasses(inst, true);
    }

    // Legacy aliases (TransformerWatchdog and older call sites still use these).
    static int retransformPig2Classes(Instrumentation inst, boolean forceScan) {
        return retransformHostileClasses(inst, forceScan);
    }

    static int retransformPig2Classes(Instrumentation inst) {
        return retransformHostileClasses(inst, true);
    }

    static void retransformImportantClasses(Instrumentation inst) {
        String[] targets = PROTECTED_MC_CLASSES;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String className = c.getName().replace('.', '/');
            boolean matches = false;
            for (String t : targets) { if (className.equals(t)) { matches = true; break; } }
            if (!matches || !inst.isModifiableClass(c)) continue;
            if (FAILED_CLASSES.contains(c.getName())) continue;
            try {
                ClassLoader cl = c.getClassLoader();
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                // Inline retransform to avoid module access issues with TransformerWatchdog
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle mh = lookup.findVirtual(
                        Instrumentation.class,
                        "retransformClasses",
                        MethodType.methodType(void.class, Class[].class));
                mh.invoke(inst, new Class[]{c});
            } catch (VerifyError ve) {
                FAILED_CLASSES.add(c.getName());
                AgentLog.log("[Transfinity Runtime] Permanent VerifyError on " + c.getName() + " - blacklisted");
            } catch (Throwable t) {
                AgentLog.logThrowable("[Transfinity Runtime] FAILED to retransform " + c.getName(), t);
            }
        }
    }

    private static void retransformDirtyProtectedClasses(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String cn = c.getName().replace('.', '/');
            boolean tracked = false;
            for (String t : PROTECTED_MC_CLASSES) if (cn.equals(t)) { tracked = true; break; }
            if (!tracked || !inst.isModifiableClass(c)) continue;
            if (FAILED_CLASSES.contains(c.getName())) continue;

            if (PATCHED_MC_CLASSES.contains(c.getName())) continue;

            try {
                ClassLoader cl = c.getClassLoader();
                if (cl == null) continue;
                Thread.currentThread().setContextClassLoader(cl);
                inst.retransformClasses(c);
            } catch (VerifyError ve) {
                FAILED_CLASSES.add(c.getName());
                AgentLog.log("[Transfinity Runtime] VerifyError retransforming " + cn
                        + " during fingerprint heal - blacklisted");
            } catch (Throwable t) {
                AgentLog.logThrowable("[Transfinity Runtime] fingerprint check failed for " + cn, t);
            }
        }
    }

    private static String armorLockStatusLine() {
        try {
            Class<?> bootstrapGuard = Class.forName("runtime.ArmorLockGuard", true, null);
            Object state = bootstrapGuard.getMethod("statusLine").invoke(null);
            return String.valueOf(state) + " via-bootstrap";
        } catch (Throwable t) {
            // Not running from an exposed jar (e.g. exploded classes in the IDE):
            // the local app-loader copy is all there is.
            try {
                return ArmorLockGuard.statusLine() + " via-app";
            } catch (Throwable t2) {
                return "armor-lock-state-unreadable: " + t2;
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

                if (iteration % 10 == 0) {
                    AgentLog.log("[Transfinity] " + armorLockStatusLine());
                }

                int rem = iteration % 20;
                if (rem == 0 || (iteration <= 20
                        && (iteration == 2 || iteration == 4 || iteration == 8 || iteration == 14))) {
                    retransformDirtyProtectedClasses(inst);
                }

                if (iteration % 60 == 0) {
                    int newlyMarked = ThreadSanitizer.killAll();
                    retransformHostileClasses(inst, newlyMarked > 0);
                    ForgeLauncherGuard.protect();
                    EventBusFixer.restore();
                }

                String signal = System.getProperty("transfinity.transformer.needs_resurrection");
                if (signal != null) {
                    System.clearProperty("transfinity.transformer.needs_resurrection");
                    if (godTransformer != null) {
                        try {
                            inst.addTransformer(godTransformer, true);
                            // Resurrection -> force full scan since we were blind while dead
                            retransformHostileClasses(inst, true);
                            AgentLog.log("[Transfinity Healer] GodTransformer Healed + full retransform done");
                        } catch (Exception e) {
                            AgentLog.log("[Transfinity Healer] Healing failed: " + e.getMessage());
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

                if (cn.startsWith("runtime/") || cn.startsWith("org/luaj/")) return null;

                if (HostileRegistry.isHostileClassName(cn)) {
                    if (classBeingRedefined != null) {
                        registerHostileClass(classBeingRedefined, classfileBuffer);
                    }
                    return RuntimePatch.patchHostileClass(classfileBuffer, cn);
                }

                if (classBeingRedefined == null) return null;
                byte[] patched = patchProtectedClass(cn, classfileBuffer);
                if (patched == null) return null;

                if (PATCHED_MC_CLASSES.contains(cn)) return null;

                if (Arrays.equals(patched, classfileBuffer)) {
                    return patched;
                }

                PATCHED_MC_CLASSES.add(cn);
                return patched;
            } catch (Throwable t) {
                AgentLog.logThrowable("[GodTransformer] Error transforming " + className, t);
                return null;
            }
        }

        private static byte[] patchProtectedClass(String cn, byte[] classfileBuffer) {
            if (cn.equals("net/minecraft/world/entity/LivingEntity"))
                return RuntimePatch.patchLivingEntity(classfileBuffer);
            if (cn.equals("net/minecraft/world/entity/Entity"))
                return RuntimePatch.patchEntity(classfileBuffer);
            if (cn.equals("net/minecraft/world/entity/player/Player"))
                return RuntimePatch.patchPlayer(classfileBuffer);
            if (cn.equals("net/minecraft/world/entity/player/Inventory"))
                return RuntimePatch.patchInventory(classfileBuffer);
            if (cn.equals("net/minecraft/world/item/ItemStack"))
                return RuntimePatch.patchItemStack(classfileBuffer);
            if (cn.equals("net/minecraft/network/syncher/SynchedEntityData"))
                return RuntimePatch.patchSynchedEntityData(classfileBuffer);
            if (cn.equals("net/minecraft/server/level/ServerPlayer"))
                return RuntimePatch.patchServerPlayer(classfileBuffer);
            if (cn.equals("net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback"))
                return RuntimePatch.patchEntityCallback(classfileBuffer);
            if (cn.equals("net/minecraft/server/network/ServerGamePacketListenerImpl"))
                return RuntimePatch.patchServerGamePacketListener(classfileBuffer);
            if (cn.equals("net/minecraft/world/ContainerHelper"))
                return RuntimePatch.patchContainerHelper(classfileBuffer);
            if (cn.equals("net/minecraft/core/NonNullList"))
                return RuntimePatch.patchNonNullList(classfileBuffer);
            if (cn.equals("net/minecraft/core/MappedRegistry"))
                return RuntimePatch.patchMappedRegistry(classfileBuffer);
            return null;
        }
    }
}