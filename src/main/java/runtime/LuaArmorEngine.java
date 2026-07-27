package runtime;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class LuaArmorEngine {

    private static final String SCRIPT_RESOURCE = "/scripts/lua/armor_invuln.lua";
    private static final String SCRIPT_PATH_PROP = "transfinity.lua.script";

    private static volatile LuaArmorEngine INSTANCE = null;
    private static final Object INIT_LOCK = new Object();

    private static final ConcurrentHashMap<String, Boolean> godEntities =
            new ConcurrentHashMap<>();

    private final Globals globals;
    private final LuaValue armorCheckFn;

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicLong callCount = new AtomicLong(0);

    private LuaArmorEngine() {

        globals = JsePlatform.standardGlobals();

        injectJavaBridge(globals);

        String script = loadScript();

        globals.load(script).call();

        LuaValue fn = globals.get("checkArmorInvuln");

        if (fn == null || fn.isnil()) {
            throw new RuntimeException(
                    "[LuaArmorEngine] armor_invuln.lua missing checkArmorInvuln function"
            );
        }

        armorCheckFn = fn;
    }

    public static LuaArmorEngine getInstance() {

        if (INSTANCE == null) {

            synchronized (INIT_LOCK) {

                if (INSTANCE == null) {

                    try {
                        INSTANCE = new LuaArmorEngine();

                    } catch (Exception e) {

                        System.err.println(
                                "[LuaArmorEngine] FAILED to init: " + e.getMessage()
                        );

                        e.printStackTrace();
                    }
                }
            }
        }

        return INSTANCE;
    }

    public static void start() {

        Thread t = new Thread(() -> {

            getInstance();

            monitorLoop();

        }, "Transfinity-Lua-Engine");

        t.setDaemon(true);

        t.start();

        System.out.println("[Transfinity Lua] Engine thread started");
    }

    public boolean vetoCheck(String entityUUID, float damage, String armorTag) {

        LuaArmorEngine eng = INSTANCE;

        if (eng == null || !eng.healthy.get()) {
            return false;
        }

        try {

            callCount.incrementAndGet();

            LuaValue result = eng.armorCheckFn.call(
                    LuaValue.valueOf(entityUUID),
                    LuaValue.valueOf(damage),
                    LuaValue.valueOf(armorTag)
            );

            return result.toboolean();

        } catch (Exception e) {

            System.err.println(
                    "[LuaArmorEngine] vetoCheck error: " + e.getMessage()
            );

            eng.healthy.set(false);

            return false;
        }
    }

    public static void registerGodEntity(String uuid) {
        godEntities.put(uuid, Boolean.TRUE);
    }

    public static void unregisterGodEntity(String uuid) {
        godEntities.remove(uuid);
    }

    public static boolean isGodEntity(String uuid) {
        return Boolean.TRUE.equals(godEntities.get(uuid));
    }

    private void injectJavaBridge(Globals g) {

        g.set("java_isGodEntity", new OneArgFunction() {

            @Override
            public LuaValue call(LuaValue arg) {

                String uuid = arg.toString();

                return LuaValue.valueOf(
                        isGodEntity(uuid)
                );
            }
        });

        g.set("java_registerGod", new OneArgFunction() {

            @Override
            public LuaValue call(LuaValue arg) {

                registerGodEntity(
                        arg.toString()
                );

                return LuaValue.TRUE;
            }
        });

        g.set("java_log", new OneArgFunction() {

            @Override
            public LuaValue call(LuaValue arg) {

                System.out.println(
                        "[Transfinity Lua Script] " + arg.toString()
                );

                return LuaValue.NIL;
            }
        });

        g.set("java_getCallCount", new ZeroArgFunction() {

            @Override
            public LuaValue call() {

                return LuaValue.valueOf(
                        (double) callCount.get()
                );
            }
        });
    }

    private String loadScript() {
        String extPath = System.getProperty(SCRIPT_PATH_PROP);

        if (extPath != null) {

            try {

                byte[] bytes = java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(extPath)
                );

                return new String(bytes, StandardCharsets.UTF_8);

            } catch (Exception e) {

                System.err.println(
                        "[LuaArmorEngine] External script failed, falling back to resource"
                );
            }
        }

        try (InputStream is =
                     LuaArmorEngine.class.getResourceAsStream(SCRIPT_RESOURCE)) {

            if (is == null) {

                throw new RuntimeException(
                        "Resource not found: " + SCRIPT_RESOURCE
                );
            }

            return new String(
                    is.readAllBytes(),
                    StandardCharsets.UTF_8
            );

        } catch (Exception e) {

            System.err.println(
                    "[LuaArmorEngine] Failed to load script resource: "
                            + e.getMessage()
            );

            return FALLBACK_SCRIPT;
        }
    }

    private static void monitorLoop() {

        while (true) {

            try {

                Thread.sleep(2000);

                LuaArmorEngine eng = INSTANCE;

                if (eng != null && !eng.healthy.get()) {

                    System.out.println(
                            "[LuaArmorEngine] Detected in bad state? reinitializing..."
                    );

                    synchronized (INIT_LOCK) {

                        INSTANCE = new LuaArmorEngine();
                    }

                    System.out.println(
                            "[LuaArmorEngine] Reinitialized Fine"
                    );
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                break;

            } catch (Exception e) {

                System.err.println(
                        "[LuaArmorEngine] Monitor error: " + e.getMessage()
                );
            }
        }
    }

    private static final String FALLBACK_SCRIPT =

            "function checkArmorInvuln(uuid, damage, armorTag)\n" +
                    "  if java_isGodEntity(uuid) then return true end\n" +
                    "  return false\n" +
                    "end\n";
}