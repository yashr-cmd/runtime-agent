package runtime;

/**
 * Legacy thread-sanitizer entry point. All real logic now lives in
 * HostileRegistry — the decision of "who is hostile" is the registry's
 * (seeded from known threats + grown by behavior), not this class's hardcoded
 * pig2 frame markers.
 *
 * This class is kept ONLY so bytecode we already injected into already-patched
 * classes (which calls runtime/ThreadSanitizer.isCallerPig2) keeps resolving.
 * New patches inject runtime/HostileRegistry.isCallerHostile instead.
 */
public class ThreadSanitizer {
    private static final String[] PIG2_FRAME_MARKERS = {
            "kakiku.pig2mod.",
            "kakiku.",
    };

    /**
     * Kills every live hostile thread (registered-hostile OR caught mid
     * transformer-kill). Returns the number of NEWLY marked hostile classes so
     * the caller can trigger a forced retransform rescan.
     */
    public static int killAll() {
        return HostileRegistry.scanAndKill();
    }

    public static boolean isCallerPig2() {
        return HostileRegistry.isCallerHostile();
    }

    public static boolean isCallerHostile() {
        return HostileRegistry.isCallerHostile();
    }

    private static boolean isPig2Thread(Thread t) {
        try {
            for (StackTraceElement frame : t.getStackTrace()) {
                String cls = frame.getClassName();
                for (String marker : PIG2_FRAME_MARKERS) {
                    if (cls.startsWith(marker)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}