package runtime;

import java.util.Arrays;

public final class TrustChecker {

    private static final String[] TRUSTED_PACKAGES = {
            "runtime.",                                  // this agent
            "net.minecraft.",
            "net.minecraftforge.",
            "net.mcreator.transfinityimproved.",          // your own mod only — NOT all of net.mcreator.,
                                                            // since hostile mods (pig2 included) are MCreator-built too
            "cpw.mods.",                                   // ModLauncher internals
            "java.util.",
            "sun.management."
    };

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private TrustChecker() {}

    public static boolean calledFromTrustedCode(int start, int end) {
        try {
            return WALKER.walk(stack -> stack.skip(start)
                    .limit(end < 0 ? Long.MAX_VALUE : (long) (end - start))
                    .noneMatch(TrustChecker::isUntrustedFrame));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean calledFromTrustedCode() {
        return calledFromTrustedCode(1, 7);
    }

    private static boolean isUntrustedFrame(StackWalker.StackFrame frame) {
        String cls = frame.getClassName();
        if (cls.startsWith("java.lang.reflect.") || cls.startsWith("java.lang.invoke.")) {
            return true;
        }
        return Arrays.stream(TRUSTED_PACKAGES).noneMatch(cls::startsWith);
    }
}