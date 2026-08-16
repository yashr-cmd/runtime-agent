package runtime.bootstrap;

import java.util.List;

/**
 * Describes how to relaunch the current JVM.
 *
 * Launcher-specific handling is isolated behind this interface: the bootstrap
 * core (NativeBootstrap) only ever talks to a LaunchContext and never assumes a
 * particular Minecraft launcher. The shipped default (JvmLaunchContext) derives
 * everything from the live JVM state, which works with any launcher. A future
 * launcher-specific adapter (e.g. one that rewrites a Prism / Modrinth /
 * vanilla-launcher profile or config file) can be added without touching the
 * bootstrap core — the failure contract is the same: if the mechanism cannot be
 * identified or safely driven, the bootstrap logs the reason and continues
 * without the native layer.
 */
public interface LaunchContext {

    /** Absolute path of the java executable to launch. */
    String getJavaBinary();

    /** Existing JVM arguments to preserve, exactly as given to this JVM. */
    List<String> getJvmArguments();

    /** Main class to launch. */
    String getMainClass();

    /** Program arguments passed to the main class. */
    List<String> getProgramArguments();

    /** Short human-readable description for logging. */
    String describe();
}
