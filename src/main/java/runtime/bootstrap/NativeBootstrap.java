package runtime.bootstrap;

import runtime.AgentLog;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safe native (JVMTI) bootstrap + relaunch mechanism for the DTT runtime agent.
 *
 * Everything here is fail-open: any problem is logged and the agent continues
 * WITHOUT the native layer — Minecraft never crashes because the bootstrap was
 * unavailable. The relaunch logic lives entirely in this package; the rest of
 * the agent (RuntimeAgent and friends) is untouched except for two idempotent
 * hooks, one in RuntimeAgent.premain() and one in RuntimeAgent.agentmain().
 * The agentmain hook matters because the real game loads this agent
 * dynamically (loadAgent -> agentmain), not via -javaagent premain.
 *
 * Flow (runs once, at the FIRST agent entry point — premain or agentmain — and
 * only once per JVM thanks to a run-once guard):
 *   1. Detect OS/CPU and select the matching bundled library (.dll/.so/.dylib,
 *      x86-64/ARM64).
 *   2. Extract + validate the library from the DTT jar into a per-user cache
 *      directory (see NativeLibraryExtractor).
 *   3. Inspect the ACTUAL JVM launch arguments (RuntimeMXBean.getInputArguments)
 *      to see whether the native -agentpath and the DTT -javaagent are already
 *      present. This inspection of the real command line is the relaunch-loop
 *      guard — a relaunched JVM carries BOTH required arguments on its actual
 *      command line, so a fresh JVM sees them and never relaunches again. No
 *      temporary/global flag is relied on for that decision.
 *   4. If the native -agentpath is already present -> no relaunch, continue
 *      normally (the native layer is active; the Java agent may arrive via
 *      -javaagent premain or dynamic attach).
 *   5. If -agentpath is missing -> spawn a NEW JVM process with it (adding
 *      -javaagent too when that is also missing), with the native -agentpath
 *      placed BEFORE the -javaagent so the startup order is native JVMTI agent
 *      -> DTT Java premain -> Minecraft. Every existing JVM argument is
 *      preserved; nothing is duplicated. The new process is verified to still
 *      be alive after native-agent load time before this JVM exits; if the
 *      child dies immediately, this JVM continues normally WITHOUT the native
 *      layer.
 *
 * Launcher-managed games (Modrinth's theseus wrapper, detected by main class)
 * are relaunched with the REAL game main instead of the wrapper: the wrapper's
 * RPC connection belongs to the ORIGINAL process, so a child running the wrapper
 * would die with Connection refused. JvmLaunchContext substitutes the real game
 * main (the class the wrapper reflectively invokes after its RPC setup), so the
 * child is an ordinary game launch that never touches the launcher IPC. If the
 * real main cannot be found on the classpath the launch fails open — the game
 * runs without the native layer, and a stable library path is logged for pasting
 * into the launcher's JVM arguments.
 *
 * Design note: no launcher configuration file is ever read or modified. All
 * launch data comes from the running JVM (JvmLaunchContext), which makes the
 * bootstrap launcher-agnostic; the LaunchContext interface is the seam where a
 * launcher-specific adapter could be added later. No system settings, registry
 * entries, startup configuration, or any persistent OS configuration are touched
 * — the only thing written is our own per-user library cache.
 */
public final class NativeBootstrap {

    /** Set on the relaunched JVM command line. Informational + extra loop-breaker. */
    public static final String RELAUNCH_PROPERTY = "transfinity.native.relaunched";

    /** Opt-out: -Dtransfinity.native.bootstrap=false disables the whole mechanism. */
    public static final String DISABLE_PROPERTY = "transfinity.native.bootstrap";

    /** How long to give the child to prove it is alive past native-agent load. */
    private static final long CHILD_SURVIVAL_MS = 2000L;
    private static final long CHILD_POLL_MS = 50L;

    private static final AtomicBoolean RUN_GUARD = new AtomicBoolean(false);

    private NativeBootstrap() {}

    /**
     * Runs the bootstrap. Returns true only when this JVM has been relaunched and
     * is exiting — the caller must NOT continue normal startup. Returns false to
     * continue normally: either the native layer is already in place, or the
     * bootstrap could not be completed safely.
     */
    public static boolean ensure() {
        if (!RUN_GUARD.compareAndSet(false, true)) {
            AgentLog.log("[NativeBootstrap] ensure() already ran in this JVM — skipping");
            return false;
        }

        if (runBootstrap()) {
            AgentLog.log("[NativeBootstrap] relaunch started successfully — halting this JVM so the child owns Minecraft");
            // halt() (not System.exit()) deliberately skips shutdown hooks: the
            // game's main thread is still mid-boot here, and running its hooks
            // while it is still registering them throws the "Shutdown in
            // progress" uncaught exception that launchers flag as a crash.
            Runtime.getRuntime().halt(0);
            return true; // unreachable in practice
        }
        return false;
    }

    /**
     * Same bootstrap as ensure(), but for the dynamic-attach path (agentmain).
     *
     * Returns true as soon as a relaunch has been scheduled; the actual
     * halt (Runtime.halt, skipping shutdown hooks) happens ~250 ms later on a
     * background thread. Returning from
     * agentmain BEFORE the JVM dies lets the loadAgent() call that triggered us
     * complete normally, so the mod's attach-helper process exits cleanly
     * instead of hanging forever on a VM that was killed mid-attach.
     */
    public static boolean ensureAsync() {
        if (!RUN_GUARD.compareAndSet(false, true)) {
            AgentLog.log("[NativeBootstrap] ensure() already ran in this JVM — skipping");
            return false;
        }

        if (runBootstrap()) {
            AgentLog.log("[NativeBootstrap] relaunch started successfully — halting this JVM on a background"
                    + " thread so the pending attach call completes cleanly");
            Thread exiter = new Thread(NativeBootstrap::exitSoon, "DTT-NativeBootstrap-Exit");
            exiter.setDaemon(false);
            exiter.start();
            return true;
        }
        return false;
    }

    private static boolean runBootstrap() {
        if ("false".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY))) {
            AgentLog.log("[NativeBootstrap] disabled by -D" + DISABLE_PROPERTY
                    + "=false — continuing without the native layer");
            return false;
        }

        // Java-version diagnostics: the bootstrap is built for Java 17+ and uses
        // only APIs available since Java 9, so it runs identically on 17, 21 and
        // later. Log the version so a too-old-launcher issue is visible here
        // instead of showing up later as a random class-version error.
        AgentLog.log("[NativeBootstrap] JVM: " + System.getProperty("java.version")
                + " (release " + Runtime.version().feature() + ", home=" + System.getProperty("java.home") + ")");

        try {
            return run();
        } catch (Throwable t) {
            AgentLog.logThrowable("[NativeBootstrap] unexpected bootstrap error — continuing without the native layer", t);
            return false;
        }
    }

    private static void exitSoon() {
        try {
            // Give the pending loadAgent()/attach protocol time to return its
            // result to the helper process before this JVM is torn down.
            Thread.sleep(250L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // halt() (not System.exit()) deliberately skips shutdown hooks. The
        // game's main thread is still booting here; System.exit would run its
        // shutdown hooks and throw "Shutdown in progress" when it hits the
        // half-registered state — that uncaught exception is what made Modrinth
        // flag the relaunch as a crash. halt(0) exits cleanly with code 0.
        Runtime.getRuntime().halt(0);
    }

    private static boolean run() {
        NativePlatform platform = NativePlatform.detect();
        AgentLog.log("[NativeBootstrap] detected OS=" + platform.os() + " arch=" + platform.arch());
        if (!platform.isSupported()) {
            AgentLog.log("[NativeBootstrap] unsupported platform " + platform.describe()
                    + " — continuing without the native layer");
            return false;
        }
        AgentLog.log("[NativeBootstrap] selected native library: " + platform.fileName()
                + " (resource " + platform.resourcePath() + ")");

        File agentJar = locateAgentJar();
        if (agentJar == null) {
            AgentLog.log("[NativeBootstrap] cannot locate this agent's jar (running from exploded classes?)"
                    + " — continuing without the native layer");
            return false;
        }
        AgentLog.log("[NativeBootstrap] agent jar: " + agentJar.getAbsolutePath());
        String tmpDir = System.getProperty("java.io.tmpdir", "").replace('\\', '/');
        String jarPath = agentJar.getAbsolutePath().replace('\\', '/');
        if (!tmpDir.isEmpty() && jarPath.startsWith(tmpDir)) {
            AgentLog.log("[NativeBootstrap] WARNING: agent jar is a temporary copy (dynamic-attach flow)"
                    + " — the relaunched JVM will load premain from it; if the mod deletes it before the"
                    + " child starts, the child may fail to find the agent");
        }

        List<String> jvmArgs = currentJvmArguments();
        if (jvmArgs == null) {
            AgentLog.log("[NativeBootstrap] cannot read the current JVM launch arguments"
                    + " — continuing without the native layer");
            return false;
        }

        boolean agentPathPresent = containsOurAgentPath(jvmArgs, platform);
        boolean javaAgentPresent = containsOurJavaAgent(jvmArgs, agentJar);

        AgentLog.log("[NativeBootstrap] -agentpath already present: " + agentPathPresent);
        AgentLog.log("[NativeBootstrap] -javaagent (DTT agent) already present: " + javaAgentPresent);

        // The native layer is the whole point of a relaunch: if -agentpath is
        // already on the real command line, the JVMTI agent loaded at JVM start
        // and there is nothing left to relaunch for — the Java agent may
        // legitimately be absent from the command line because the mod loads it
        // dynamically (loadAgent -> agentmain).
        if (agentPathPresent) {
            if (javaAgentPresent) {
                AgentLog.log("[NativeBootstrap] both required arguments already present"
                        + " — initialization already happened, continuing without relaunch");
            } else {
                AgentLog.log("[NativeBootstrap] native layer already active (-agentpath present)"
                        + " — continuing without relaunch");
            }
            return false;
        }

        // Belt-and-suspenders loop breaker: this property is only ever set by OUR
        // own relaunch command line. If it is set here and -agentpath still isn't
        // visible (e.g. an environment that strips VM args), do NOT relaunch a
        // second time — log and continue without the native layer.
        if ("true".equalsIgnoreCase(System.getProperty(RELAUNCH_PROPERTY))) {
            AgentLog.log("[NativeBootstrap] already relaunched once (transfinity.native.relaunched=true)"
                    + " but -agentpath is still missing — aborting to prevent a relaunch loop;"
                    + " continuing without the native layer");
            return false;
        }

        // Dedicated servers CANNOT use the relaunch strategy: they are started
        // and supervised by systemd, Docker/Pterodactyl or a process manager
        // that expects exactly one long-lived PID. If that PID exits — even
        // cleanly, even to spawn a working replacement a moment later — the
        // supervisor marks the service "stopped"/"crashed" (and in a plain
        // Docker container, java as PID 1, the whole container is torn down,
        // killing the replacement before it finishes booting). Instead, load
        // the native library into THIS process in-place via the JDK Attach API
        // (external helper, see ServerNativeAttach): the PID never changes and
        // the process never exits. Fail-open: a failed attach just logs and the
        // server continues without the native layer, same as every other path.
        if (ServerLaunchDetector.isDedicatedServer()) {
            AgentLog.log("[NativeBootstrap] dedicated server detected — using in-place native attach instead of relaunch");
            Path serverLib = NativeLibraryExtractor.extract(agentJar, platform);
            if (serverLib == null) {
                AgentLog.log("[NativeBootstrap] native library could not be extracted for in-place attach"
                        + " — continuing without the native layer");
                return false;
            }
            AgentLog.log("[NativeBootstrap] in-place attach library: " + pathWithSize(serverLib));
            boolean attached = ServerNativeAttach.attach(serverLib, null);
            AgentLog.log("[NativeBootstrap] in-place native attach " + (attached ? "succeeded" : "failed")
                    + " — continuing in this JVM (no process relaunch on a server)");
            // Never halt a server process: the attach already happened in-place,
            // and there is no child to hand over to. Always return false so the
            // caller continues normal startup.
            return false;
        }

        // Launcher-managed games (Modrinth's theseus wrapper is the known case)
        // cannot relaunch the launcher's own wrapper main: its RPC connection
        // belongs to the ORIGINAL process, so a child running the wrapper dies
        // with Connection refused. JvmLaunchContext substitutes the REAL game
        // main (the class the launcher reflectively invokes after its RPC
        // setup), so the child is an ordinary game launch that never touches the
        // launcher IPC. If no real main is on the classpath, fromCurrentJvm()
        // returns null and the launch fails open below.
        if (JvmLaunchContext.isLauncherWrappedMain(mainClassFromSunJavaCommand())) {
            AgentLog.log("[NativeBootstrap] launcher-managed launch detected — the relaunched JVM will"
                    + " run the real game main instead of the launcher wrapper");
        }

        AgentLog.log("[NativeBootstrap] relaunch required: -agentpath present=false,"
                + " DTT -javaagent present=" + javaAgentPresent);

        Path nativeLib = null;
        if (!agentPathPresent) {
            AgentLog.log("[NativeBootstrap] extracting native library...");
            nativeLib = NativeLibraryExtractor.extract(agentJar, platform);
            if (nativeLib == null) {
                AgentLog.log("[NativeBootstrap] native library could not be extracted / located / validated"
                        + " — continuing without the native layer");
                return false;
            }
            AgentLog.log("[NativeBootstrap] extraction result: " + pathWithSize(nativeLib));

            // Stable, hardcode-able location for launchers where the wrapper
            // cannot be adapted: paste this -agentpath into the launcher's JVM
            // arguments and the native layer activates without a relaunch, even
            // after the agent jar is rebuilt.
            Path stable = NativeLibraryExtractor.stablePath(platform);
            if (stable != null) {
                try {
                    Files.createDirectories(stable.getParent());
                    Files.copy(nativeLib, stable, StandardCopyOption.REPLACE_EXISTING);
                    AgentLog.log("[NativeBootstrap] stable native library path for launcher config: " + stable);
                } catch (java.io.IOException e) {
                    AgentLog.log("[NativeBootstrap] could not write stable native library path: "
                            + e.getMessage());
                }
            }
        }

        JvmLaunchContext ctx = JvmLaunchContext.fromCurrentJvm();
        if (ctx == null) {
            AgentLog.log("[NativeBootstrap] launch mechanism could not be identified safely"
                    + " (unresolvable launcher wrapper / java binary / main class unavailable)"
                    + " — continuing without the native layer");
            return false;
        }
        AgentLog.log("[NativeBootstrap] launch context: " + ctx.describe());

        List<String> command = buildCommand(ctx, agentJar, nativeLib, agentPathPresent, javaAgentPresent);
        AgentLog.log("[NativeBootstrap] relaunch command: " + describeCommand(command));

        return startRelaunch(command);
    }

    // ------------------------------------------------------------------
    // Relaunch
    // ------------------------------------------------------------------

    private static boolean startRelaunch(List<String> command) {
        Process child;
        try {
            // Environment is inherited by default, so the child keeps the exact
            // same environment and working directory as this JVM.
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            child = pb.start();
            AgentLog.log("[NativeBootstrap] relaunch process started (pid=" + child.pid() + ")");
        } catch (Throwable t) {
            AgentLog.logThrowable("[NativeBootstrap] FAILED to start the relaunch process"
                    + " — continuing without the native layer in this JVM", t);
            return false;
        }

        // Bounded wait for the child to survive past native-agent load time. The
        // JVMTI agent's Agent_OnLoad runs synchronously at JVM start, so a child
        // still alive after this window has loaded the native layer successfully.
        // A child that dies immediately means the relaunch failed (bad library,
        // arg problem, ...) — in that case this JVM continues WITHOUT the native
        // layer instead of silently killing the game.
        long deadline = System.currentTimeMillis() + CHILD_SURVIVAL_MS;
        try {
            while (System.currentTimeMillis() < deadline && child.isAlive()) {
                Thread.sleep(CHILD_POLL_MS);
            }
            if (child.isAlive()) {
                AgentLog.log("[NativeBootstrap] relaunch SUCCESS: child still alive after "
                        + CHILD_SURVIVAL_MS + " ms — native layer is loading in the child");
                return true;
            }
            int exit = child.exitValue();
            if (exit == 0) {
                AgentLog.log("[NativeBootstrap] relaunch child exited cleanly (code 0) before the "
                        + CHILD_SURVIVAL_MS + " ms survival window elapsed — cannot confirm the native layer"
                        + " is serving a session; continuing WITHOUT the native layer in this JVM");
            } else {
                AgentLog.log("[NativeBootstrap] relaunch FAILURE: child exited before native-agent load (exit code "
                        + exit + ") — continuing WITHOUT the native layer in this JVM");
            }
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            AgentLog.log("[NativeBootstrap] interrupted while monitoring the relaunched child"
                    + " — continuing without the native layer");
            return false;
        }
    }

    private static List<String> buildCommand(JvmLaunchContext ctx, File agentJar, Path nativeLib,
                                             boolean agentPathPresent, boolean javaAgentPresent) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ctx.getJavaBinary());

        // Native layer FIRST so the startup order is: native JVMTI agent ->
        // this premain (via -javaagent) -> Minecraft.
        if (!agentPathPresent && nativeLib != null) {
            cmd.add("-agentpath:" + nativeLib.toAbsolutePath());
        }

        // Preserve every existing JVM argument exactly — nothing dropped,
        // nothing duplicated.
        cmd.addAll(ctx.getJvmArguments());

        if (!javaAgentPresent) {
            cmd.add("-javaagent:" + agentJar.getAbsolutePath());
        }

        // Informational + extra loop-breaker for the child (see run()).
        cmd.add("-D" + RELAUNCH_PROPERTY + "=true");

        cmd.add(ctx.getMainClass());
        cmd.addAll(ctx.getProgramArguments());
        return cmd;
    }

    // ------------------------------------------------------------------
    // Detection helpers (all against the REAL current JVM arguments)
    // ------------------------------------------------------------------

    private static List<String> currentJvmArguments() {
        try {
            return new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mainClassFromSunJavaCommand() {
        String command = System.getProperty("sun.java.command", "");
        if (command == null || command.isBlank()) return null;
        return command.trim().split("\\s+")[0];
    }

    /** True when a -agentpath pointing at OUR extracted library is on the command line. */
    private static boolean containsOurAgentPath(List<String> args, NativePlatform platform) {
        String expected = platform.fileName() == null ? "" : platform.fileName().toLowerCase(Locale.ROOT);
        if (expected.isEmpty()) return false;
        for (String arg : args) {
            String path = stripAgentPrefix(arg, "-agentpath:");
            if (path == null) path = stripAgentPrefix(arg, "-agentpath=");
            if (path == null) continue;
            String name = new File(path).getName().toLowerCase(Locale.ROOT);
            if (name.equals(expected)) return true;
        }
        return false;
    }

    /** True when a -javaagent pointing at THIS agent jar is on the command line. */
    private static boolean containsOurJavaAgent(List<String> args, File agentJar) {
        Path ourJar = agentJar.toPath().toAbsolutePath().normalize();
        String ourName = agentJar.getName().toLowerCase(Locale.ROOT);
        for (String arg : args) {
            String path = stripAgentPrefix(arg, "-javaagent:");
            if (path == null) path = stripAgentPrefix(arg, "-javaagent=");
            if (path == null) continue;
            int eq = path.indexOf('=');
            if (eq >= 0) path = path.substring(0, eq); // strip -javaagent:jar=options
            if (path.isEmpty()) continue;
            try {
                Path p = new File(path).toPath().toAbsolutePath().normalize();
                if (p.equals(ourJar)) return true;
            } catch (Throwable ignored) {}
            if (new File(path).getName().toLowerCase(Locale.ROOT).equals(ourName)) return true;
        }
        return false;
    }

    private static String stripAgentPrefix(String arg, String prefix) {
        if (arg.startsWith(prefix)) return arg.substring(prefix.length());
        return null;
    }

    private static File locateAgentJar() {
        try {
            File f = new File(NativeBootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return f.getCanonicalFile();
            }
        } catch (Throwable t) {
            AgentLog.logThrowable("[NativeBootstrap] failed to locate the agent jar", t);
        }
        return null;
    }

    private static String pathWithSize(Path path) {
        try {
            return path.toAbsolutePath() + " (" + Files.size(path) + " bytes)";
        } catch (Throwable t) {
            return String.valueOf(path.toAbsolutePath());
        }
    }

    private static String describeCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            String part = command.get(i);
            // Never write session secrets into the log: the relaunch command carries
            // the user's Minecraft access token and client id, which must NOT land in
            // transfinity-agent.log (or any other log). The real child process still
            // receives the true values — only this human-readable DESCRIPTION is
            // censored.
            if (isSecretFlag(part) && i + 1 < command.size()) {
                appendCommandPart(sb, part);
                sb.append(" ***redacted***");
                i++;
                continue;
            }
            appendCommandPart(sb, part);
        }
        return sb.toString();
    }

    private static boolean isSecretFlag(String part) {
        return part.equals("--accessToken") || part.equals("--clientId");
    }

    private static void appendCommandPart(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(' ');
        if (part.indexOf(' ') >= 0) sb.append('"').append(part).append('"');
        else sb.append(part);
    }
}
