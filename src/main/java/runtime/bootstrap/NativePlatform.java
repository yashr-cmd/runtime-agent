package runtime.bootstrap;

import java.util.Locale;

/**
 * Maps the host OS + CPU architecture to the JVMTI agent library bundled in the
 * DTT jar. Single source of truth for the platform decision used by the
 * bootstrap (extraction + relaunch) and by any future launcher adapter.
 *
 * Bundled layout (src/main/resources/JVMTI_AGENT):
 *   windows/dtt_agent-win-x64.dll
 *   windows/dtt_agent-win-arm64.dll
 *   linux/libdtt_agent-linux-x64.so
 *   linux/libdtt_agent-linux-arm64.so
 *   apple/libdtt_agent-macos-universal.dylib
 *
 * macOS ships a single universal binary, so architecture selection is skipped
 * there (the same .dylib is used for both x86-64 and ARM64).
 */
public final class NativePlatform {

    public enum Os { WINDOWS, LINUX, MACOS, UNKNOWN }
    public enum Arch { X86_64, ARM64, UNKNOWN }

    private final Os os;
    private final Arch arch;

    private NativePlatform(Os os, Arch arch) {
        this.os = os;
        this.arch = arch;
    }

    public static NativePlatform detect() {
        return new NativePlatform(detectOs(), detectArch());
    }

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("linux")) return Os.LINUX;
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        return Os.UNKNOWN;
    }

    private static Arch detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return Arch.X86_64;
            case "aarch64":
            case "arm64":
                return Arch.ARM64;
            default:
                return Arch.UNKNOWN;
        }
    }

    public Os os() { return os; }
    public Arch arch() { return arch; }

    /** True when a bundled library exists for this platform/arch combination. */
    public boolean isSupported() {
        switch (os) {
            case WINDOWS:
            case LINUX:
                return arch != Arch.UNKNOWN;
            case MACOS:
                return true; // universal binary
            default:
                return false;
        }
    }

    /** Resource path of the bundled library inside the DTT jar. */
    public String resourcePath() {
        switch (os) {
            case WINDOWS:
                return "JVMTI_AGENT/windows/dtt_agent-win-" + archSuffix() + ".dll";
            case LINUX:
                return "JVMTI_AGENT/linux/libdtt_agent-linux-" + archSuffix() + ".so";
            case MACOS:
                return "JVMTI_AGENT/apple/libdtt_agent-macos-universal.dylib";
            default:
                return null;
        }
    }

    /** File name the extracted library is given in the cache directory. */
    public String fileName() {
        String rp = resourcePath();
        if (rp == null) return null;
        int idx = rp.lastIndexOf('/');
        return idx >= 0 ? rp.substring(idx + 1) : rp;
    }

    private String archSuffix() {
        return arch == Arch.ARM64 ? "arm64" : "x64";
    }

    public String describe() {
        return os + "/" + arch;
    }
}
