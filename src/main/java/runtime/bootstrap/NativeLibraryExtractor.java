package runtime.bootstrap;

import runtime.AgentLog;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Extracts the selected JVMTI agent library from the DTT jar into a per-user
 * cache directory and validates it before anything tries to load it.
 *
 * The cache dir is keyed by a short hash of the agent jar (path + size +
 * last-modified), so an updated jar automatically gets a fresh extraction while
 * an unchanged install reuses the existing copy. Extraction is
 * write-temp-then-atomic-move so a concurrently starting JVM can never observe
 * a partially written library.
 *
 * All writes stay inside a per-user cache location (LOCALAPPDATA / XDG_CACHE_HOME
 * / ~/Library/Caches / tmpdir). Nothing outside our own cache is touched, and no
 * system/registry/startup configuration is ever modified.
 */
public final class NativeLibraryExtractor {

    private NativeLibraryExtractor() {}

    /**
     * @return the validated extracted library path, or null if the library could
     *         not be located in the jar, extracted, or validated.
     */
    public static Path extract(File agentJar, NativePlatform platform) {
        String resourcePath = platform.resourcePath();
        if (resourcePath == null) return null;

        Path dir = cacheDir(agentJar);
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            AgentLog.logThrowable("[NativeBootstrap] failed to create native-library cache dir " + dir, t);
            return null;
        }

        Path target = dir.resolve(platform.fileName());
        if (isUsable(target, platform)) {
            AgentLog.log("[NativeBootstrap] native library already cached and valid: " + target);
            return target;
        }

        InputStream in = openResource(resourcePath);
        if (in == null) {
            AgentLog.log("[NativeBootstrap] bundled native library resource not found: " + resourcePath);
            return null;
        }

        try (InputStream stream = in) {
            Path tmp = Files.createTempFile(dir, platform.fileName(), ".tmp");
            try {
                Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ame) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Throwable ignored) {}
            }
            AgentLog.log("[NativeBootstrap] extracted " + resourcePath + " -> " + target);
        } catch (Throwable t) {
            AgentLog.logThrowable("[NativeBootstrap] extraction of " + resourcePath + " FAILED", t);
            return null;
        }

        if (!isUsable(target, platform)) {
            AgentLog.log("[NativeBootstrap] extracted native library failed validation: " + target);
            return null;
        }
        return target;
    }

    /**
     * Validation: the file must exist, be a regular readable file, be non-empty,
     * and carry the platform's expected magic bytes (PE/ELF/Mach-O). A library
     * that fails any of these is never handed to the relaunched JVM.
     */
    public static boolean isUsable(Path path, NativePlatform platform) {
        if (path == null) return false;
        try {
            if (!Files.isRegularFile(path)) return false;
            if (!Files.isReadable(path)) return false;
            if (Files.size(path) <= 0) return false;
            return matchesMagic(path, platform);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean matchesMagic(Path path, NativePlatform platform) {
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(path)) {
            int n = in.read(magic);
            if (n < 2) return false;
            switch (platform.os()) {
                case WINDOWS:
                    return magic[0] == 'M' && magic[1] == 'Z';
                case LINUX:
                    return n >= 4
                            && magic[0] == 0x7F && magic[1] == 'E'
                            && magic[2] == 'L' && magic[3] == 'F';
                case MACOS:
                    return n >= 4 && isMachO(magic);
                default:
                    return true; // unknown OS — don't block on magic bytes
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMachO(byte[] m) {
        // 32/64-bit Mach-O, both big-endian (FE ED FA ...) and little-endian
        // (CF FA ED FE / CE FA ED FE) magic values.
        return (m[0] == (byte) 0xFE && m[1] == (byte) 0xED && m[2] == (byte) 0xFA && (m[3] == (byte) 0xCF || m[3] == (byte) 0xCE))
                || ((m[0] == (byte) 0xCF || m[0] == (byte) 0xCE) && m[1] == (byte) 0xFA && m[2] == (byte) 0xED && m[3] == (byte) 0xFE);
    }

    private static InputStream openResource(String resourcePath) {
        ClassLoader[] loaders = {
                NativeLibraryExtractor.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                InputStream in = cl.getResourceAsStream(resourcePath);
                if (in != null) return in;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * A stable, non-hash-keyed location for the selected library, so a launcher
     * config (e.g. Modrinth JVM arguments) can reference a constant path even
     * after the agent jar is rebuilt. Kept up to date by NativeBootstrap after
     * each successful extraction. Returns null when the platform is unsupported
     * or no cache location could be resolved.
     */
    public static Path stablePath(NativePlatform platform) {
        String name = platform.fileName();
        if (name == null || name.isEmpty()) return null;
        String base = cacheBaseDir();
        if (base == null) return null;
        return Paths.get(base, "stable", name);
    }

    private static Path cacheDir(File agentJar) {
        String base = cacheBaseDir();
        if (base == null) return null;
        try {
            return Paths.get(base, "dtt-jvmti-" + jarHash(agentJar));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cacheBaseDir() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", "");
        String tmpDir = System.getProperty("java.io.tmpdir", ".");
        try {
            if (osName.contains("win")) {
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isEmpty()) {
                    return localAppData + File.separator + "DTT";
                }
                return tmpDir;
            }
            if (osName.contains("mac")) {
                if (!userHome.isEmpty()) {
                    return userHome + File.separator + "Library" + File.separator
                            + "Caches" + File.separator + "TransfinityDTT";
                }
                return tmpDir;
            }
            // Linux and anything else: XDG cache, else ~/.cache, else tmpdir.
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isEmpty()) {
                return xdg + File.separator + "dtt";
            }
            if (!userHome.isEmpty()) {
                return userHome + File.separator + ".cache" + File.separator + "dtt";
            }
            return tmpDir;
        } catch (Throwable t) {
            return tmpDir;
        }
    }

    /**
     * Short stable hash of (jar path + size + lastModified). A jar that changed
     * on disk gets a new cache dir, so a stale extracted library can never be
     * reused after an update. Deterministic within the same installed jar.
     */
    private static String jarHash(File agentJar) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = agentJar.getAbsolutePath() + "|" + agentJar.length() + "|" + agentJar.lastModified();
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "default";
        }
    }
}
