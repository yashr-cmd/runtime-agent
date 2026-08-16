package runtime.bootstrap;

import runtime.AgentLog;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Server-safe alternative to NativeBootstrap's relaunch mechanism.
 *
 * Instead of exiting this JVM and spawning a replacement with -agentpath
 * (NativeBootstrap's client-side strategy), this loads the native library
 * into the CURRENTLY RUNNING JVM via the JDK Attach API's loadAgentPath —
 * the exact same mechanism this project already uses successfully to
 * dynamically load the Java agent (see AttacherMain). The server process's
 * PID never changes and the process never exits, so this is invisible to
 * systemd, Docker/Pterodactyl, or any other supervisor watching that PID —
 * unlike a relaunch, which can look like the service crashing (or, in a
 * plain Docker container where java is PID 1, can bring the whole container
 * down the instant the original process exits, killing the replacement
 * mid-boot too).
 *
 * Trade-off versus relaunch: Agent_OnAttach fires slightly later than
 * Agent_OnLoad would (once the attach-listener thread processes the
 * request, a little into JVM startup, rather than at the true cold-start
 * OnLoad phase) — but the process identity never changes, which is what
 * actually matters for a supervised, long-running server.
 *
 * The attach call itself runs in a small EXTERNAL helper process (spawned
 * the same way AttacherMain's external-attach mode already works for the
 * Java agent), not via VirtualMachine.attach() on our own PID from inside
 * this JVM. Self-attach requires the target JVM to have been started with
 * -Djdk.attach.allowAttachSelf=true on modern OpenJDK, which we cannot
 * guarantee for a server whose startup flags we don't control. Attaching
 * from an external process needs no such flag and is the same technique
 * this project already relies on for the Java agent.
 */
final class ServerNativeAttach {

    private ServerNativeAttach() {}

    /**
     * Attempts to load the given native library into this (server) JVM
     * in-place, without exiting or replacing the process. Returns true on
     * success. Every failure is logged and returns false — the caller
     * continues running normally without the native layer, the same
     * fail-open contract as the rest of the bootstrap.
     */
    static boolean attach(Path nativeLib, String options) {
        String javaBinary = resolveJavaBinary();
        if (javaBinary == null) {
            AgentLog.log("[ServerNativeAttach] could not resolve java binary — continuing without the native layer");
            return false;
        }

        File agentJar = locateOwnJar();
        if (agentJar == null) {
            AgentLog.log("[ServerNativeAttach] cannot locate this agent's jar (running from exploded classes?)"
                    + " — continuing without the native layer");
            return false;
        }

        String pid = String.valueOf(ProcessHandle.current().pid());
        String libPath = nativeLib.toAbsolutePath().toString();

        List<String> command = List.of(
                javaBinary,
                "-cp", agentJar.getAbsolutePath(),
                "runtime.AttacherMain",
                "nativepath",
                pid,
                libPath,
                options == null ? "" : options
        );

        AgentLog.log("[ServerNativeAttach] attaching native library in-place (pid=" + pid + ", lib=" + libPath + ")");

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process helper = pb.start();

            // The helper prints its own success/failure and exits promptly —
            // block briefly for its result instead of firing-and-forgetting,
            // so we log a clear outcome instead of guessing.
            String output = new String(helper.getInputStream().readAllBytes());
            boolean finished = helper.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                AgentLog.log("[ServerNativeAttach] attach helper did not exit within 10s — proceeding, "
                        + "outcome unconfirmed. Helper output so far:\n" + output);
                return false;
            }

            int exit = helper.exitValue();
            if (!output.isBlank()) {
                AgentLog.log("[ServerNativeAttach] attach helper output:\n" + output.strip());
            }
            if (exit == 0) {
                AgentLog.log("[ServerNativeAttach] native library attached successfully in-place");
                return true;
            }
            AgentLog.log("[ServerNativeAttach] attach helper exited with code " + exit
                    + " — continuing without the native layer");
            return false;
        } catch (Throwable t) {
            AgentLog.logThrowable("[ServerNativeAttach] FAILED to run attach helper — continuing without the native layer", t);
            return false;
        }
    }

    private static String resolveJavaBinary() {
        try {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null || javaHome.isEmpty()) return null;
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            String bin = javaHome + File.separator + "bin" + File.separator + (win ? "java.exe" : "java");
            return new File(bin).isFile() ? bin : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File locateOwnJar() {
        try {
            File f = new File(ServerNativeAttach.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                return f.getCanonicalFile();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
