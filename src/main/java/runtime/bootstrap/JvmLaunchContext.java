package runtime.bootstrap;

import runtime.AgentLog;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Launcher-agnostic LaunchContext derived from the CURRENT, already-running JVM.
 *
 *  - JVM arguments come from RuntimeMXBean.getInputArguments(), which is the
 *    authoritative, exactly-quoted list of JVM options this process was started
 *    with. Reading these (rather than a flag we wrote ourselves) is what makes
 *    relaunch-loop prevention reliable: a relaunched JVM carries the required
 *    -agentpath / -javaagent on its REAL command line. HotSpot does not list
 *    -cp/-classpath there, so it is reconstructed from java.class.path when
 *    missing — otherwise a relaunched JVM would lose its whole classpath.
 *  - The main class and program arguments are recovered from
 *    sun.java.command. NOTE: that property joins program arguments with single
 *    spaces and drops the original quoting, so an argument that contains a space
 *    (e.g. Modrinth's gameDir "…\profiles\NeoForge 1.21.8") arrives here split
 *    into several tokens. fromCurrentJvm() rejoins multi-word option values (a
 *    '-' option consumes the following non-dash tokens as its value) and, for
 *    the known Modrinth case, repairs --gameDir from the working directory when
 *    it still points nowhere. If the launch mechanism cannot be identified
 *    safely, fromCurrentJvm() returns null and the bootstrap continues without
 *    the native layer.
 */
public final class JvmLaunchContext implements LaunchContext {

    private final String javaBinary;
    private final List<String> jvmArguments;
    private final String mainClass;
    private final List<String> programArguments;

    private JvmLaunchContext(String javaBinary, List<String> jvmArguments,
                             String mainClass, List<String> programArguments) {
        this.javaBinary = javaBinary;
        this.jvmArguments = Collections.unmodifiableList(new ArrayList<>(jvmArguments));
        this.mainClass = mainClass;
        this.programArguments = Collections.unmodifiableList(new ArrayList<>(programArguments));
    }

    /**
     * Launcher wrapper main classes that CANNOT be relaunched directly: the
     * wrapper (Modrinth's theseus MinecraftLaunch is the known case) connects to
     * a launcher-side RPC socket that belongs to the ORIGINAL process, so a
     * child running the wrapper dies with Connection refused and the parent is
     * killed mid-boot — the launcher reports a crash even though the child
     * started. Instead of skipping the native layer, the bootstrap relaunches
     * the REAL game main — the class the wrapper itself reflectively invokes
     * after its RPC setup — so the child is an ordinary game launch that never
     * touches the launcher IPC.
     */
    private static final java.util.Set<String> LAUNCHER_WRAPPED_MAINS =
            Collections.singleton("com.modrinth.theseus.MinecraftLaunch");

    /**
     * Wrapper -> real game main probes, in priority order. The game main that
     * matches the actual modloader is detected on the classpath at relaunch time
     * (Class.forName with initialize=false, so probing loads nothing).
     */
    private static final String[][] LAUNCHER_ADAPTERS = {
            { "com.modrinth.theseus.MinecraftLaunch",
              "cpw.mods.bootstraplauncher.BootstrapLauncher",     // Forge / NeoForge
              "net.fabricmc.loader.impl.launch.knot.KnotClient",  // Fabric (modern)
              "net.fabricmc.loader.knot.KnotClient" }             // Fabric (legacy)
    };

    /** True when the given main class is a launcher wrapper the bootstrap must adapt. */
    public static boolean isLauncherWrappedMain(String mainClass) {
        return mainClass != null && LAUNCHER_WRAPPED_MAINS.contains(mainClass);
    }

    /**
     * Maps a launcher wrapper main to the real game main present on this JVM's
     * classpath. Returns the input unchanged when it is not a known wrapper.
     * Returns null when a known wrapper's real game main cannot be found on the
     * classpath — the bootstrap then fails open (no relaunch, no native layer).
     */
    public static String resolveRealMainForLauncherMain(String mainClass) {
        if (!isLauncherWrappedMain(mainClass)) return mainClass;
        for (String[] adapter : LAUNCHER_ADAPTERS) {
            if (!adapter[0].equals(mainClass)) continue;
            for (int i = 1; i < adapter.length; i++) {
                if (classPresent(adapter[i])) return adapter[i];
            }
        }
        return null;
    }

    private static boolean classPresent(String name) {
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                JvmLaunchContext.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class.forName(name, false, cl);
                return true;
            } catch (Throwable t) {
                // try the next loader
            }
        }
        return false;
    }

    /**
     * Builds a context from the live JVM state, or returns null when the launch
     * mechanism cannot be identified safely (missing java binary, missing main
     * class, an unresolvable launcher wrapper, or a `java -jar ...` style launch
     * that cannot be re-expressed as a main class).
     */
    public static JvmLaunchContext fromCurrentJvm() {
        String javaBinary = resolveJavaBinary();
        if (javaBinary == null) return null;

        List<String> jvmArgs = new ArrayList<>();
        try {
            jvmArgs.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            return null;
        }

        // HotSpot does NOT surface -cp/-classpath in getInputArguments(), so a
        // relaunched JVM would silently lose the entire classpath. Reconstruct
        // it from java.class.path unless a -cp/-classpath arg is preserved.
        if (!hasClasspathArg(jvmArgs)) {
            String classPath = System.getProperty("java.class.path");
            if (classPath != null && !classPath.isBlank()) {
                jvmArgs.add("-cp");
                jvmArgs.add(classPath);
            }
        }

        String command = System.getProperty("sun.java.command", "");
        if (command == null || command.trim().isEmpty()) return null;

        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0) return null;

        String mainClass = tokens[0];
        // `java -jar foo.jar ...` cannot be relaunched via a main class.
        if (mainClass.toLowerCase().endsWith(".jar")) return null;

        // Launcher wrapper (theseus): the relaunched JVM must NOT run the wrapper
        // — its RPC belongs to THIS process — so switch to the real game main
        // that is present on the classpath. If none can be found, fail open.
        if (isLauncherWrappedMain(mainClass)) {
            String realMain = resolveRealMainForLauncherMain(mainClass);
            if (realMain == null) {
                AgentLog.log("[NativeBootstrap] launcher wrapper " + mainClass
                        + " detected but no supported real game main is on the classpath"
                        + " — cannot relaunch safely; continuing without the native layer");
                return null;
            }
            AgentLog.log("[NativeBootstrap] launcher wrapper " + mainClass
                    + " detected — relaunching the real game main " + realMain);
            mainClass = realMain;
        }

        // sun.java.command joins the main class and program arguments with single
        // spaces, which DROPS the quoting of the original argv. A program argument
        // that itself contains a space (e.g. Modrinth's gameDir "NeoForge 1.21.8")
        // therefore arrives here as several tokens and must be rejoined. Rule: a
        // token starting with '-' is an option; the non-dash tokens that follow it
        // are its value. The first non-dash token after an option starts a new
        // value element, every further non-dash token continues (is merged into)
        // the previous value element.
        List<String> programArgs = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            boolean isOption = tok.startsWith("-");
            if (programArgs.isEmpty()) {
                programArgs.add(tok);
                continue;
            }
            String last = programArgs.get(programArgs.size() - 1);
            boolean lastIsOption = last.startsWith("-") && !last.contains("=");
            if (isOption || lastIsOption) {
                programArgs.add(tok);
            } else {
                programArgs.set(programArgs.size() - 1, last + " " + tok);
            }
        }

        // Safety net for the known Modrinth case: its launcher always starts the
        // game with the working directory set to the gameDir. If a split value
        // still survived rejoin and --gameDir points nowhere, use user.dir.
        repairGameDir(programArgs);

        return new JvmLaunchContext(javaBinary, jvmArgs, mainClass, programArgs);
    }

    private static void repairGameDir(List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (!args.get(i).equalsIgnoreCase("--gameDir")) continue;
            String dir = args.get(i + 1);
            if (dir != null && !new File(dir).isDirectory()) {
                String cwd = System.getProperty("user.dir");
                if (cwd != null && new File(cwd).isDirectory()) {
                    AgentLog.log("[NativeBootstrap] repaired --gameDir '" + dir + "' -> '" + cwd + "'");
                    args.set(i + 1, cwd);
                }
            }
            break;
        }
    }

    private static boolean hasClasspathArg(List<String> args) {
        for (String arg : args) {
            if (arg == null) continue;
            String a = arg.toLowerCase(Locale.ROOT);
            if (a.equals("-cp") || a.equals("-classpath")
                    || a.startsWith("-cp=") || a.startsWith("-classpath=")) {
                return true;
            }
        }
        return false;
    }

    private static String resolveJavaBinary() {
        try {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null || javaHome.isEmpty()) return null;
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            String bin = javaHome + File.separator + "bin" + File.separator + (win ? "java.exe" : "java");
            if (new File(bin).isFile()) return bin;
        } catch (Throwable t) {
            // fall through to null
        }
        return null;
    }

    @Override
    public String getJavaBinary() { return javaBinary; }

    @Override
    public List<String> getJvmArguments() { return jvmArguments; }

    @Override
    public String getMainClass() { return mainClass; }

    @Override
    public List<String> getProgramArguments() { return programArguments; }

    @Override
    public String describe() {
        return "JvmLaunchContext{java=" + javaBinary + ", main=" + mainClass
                + ", jvmArgs=" + jvmArguments.size() + ", programArgs=" + programArguments.size() + "}";
    }
}
