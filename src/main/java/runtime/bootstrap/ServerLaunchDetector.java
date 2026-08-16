package runtime.bootstrap;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the current JVM is a dedicated server rather than a game
 * client, so NativeBootstrap can pick a launch-safe strategy for each.
 *
 * WHY THIS MATTERS: NativeBootstrap's relaunch strategy (halt this JVM, spawn
 * a replacement with -agentpath added) is safe for a game client started from
 * an interactive launcher app, where nothing outside the game itself is
 * watching the process. A dedicated server is usually NOT run that way — it's
 * launched and supervised by systemd, Docker/Pterodactyl, a process manager,
 * or a start script that expects exactly one long-lived PID. If that PID
 * exits — even cleanly, even to spawn a working replacement a moment later —
 * many supervisors either mark the service "stopped"/"crashed" outright, or
 * (in a plain Docker container where java is PID 1) the whole container is
 * torn down the instant that PID exits, killing the replacement process too
 * before it can finish starting. A relaunch that is invisible on a client can
 * silently kill a server. Detecting "this is a server" lets NativeBootstrap
 * pick the safe path instead — in-place dynamic attach, no process
 * replacement (see ServerNativeAttach).
 *
 * Detection is deliberately conservative: it only reports "server" when there
 * is a clear, positive signal, and defaults to "client" (the existing,
 * already-proven relaunch path) whenever the evidence is ambiguous, so this
 * cannot regress the client flow that already works today.
 */
final class ServerLaunchDetector {

    private ServerLaunchDetector() {}

    /**
     * True when the current JVM looks like a dedicated Minecraft server
     * rather than a game client.
     */
    static boolean isDedicatedServer() {
        List<String> tokens = commandTokens();
        if (tokens == null || tokens.isEmpty()) {
            return false; // no evidence at all — assume client, the proven path
        }

        // A real Minecraft game client launch (Modrinth, the vanilla launcher,
        // Prism, ATLauncher, ...) always carries these account/session args.
        // A dedicated server launch never does — this alone is decisive, and
        // checking it FIRST means an ambiguous secondary signal (e.g. some
        // custom dev harness that happens to say "server" somewhere) can never
        // override a definite client launch.
        if (containsAny(tokens, "--accesstoken", "--uuid", "--assetsdir", "--assetindex")) {
            return false;
        }

        boolean hasNogui = containsAny(tokens, "nogui");
        boolean hasServerLaunchTarget = tokens.stream().anyMatch(t -> {
            String lower = t.toLowerCase(Locale.ROOT);
            return lower.contains("launchtarget") && lower.contains("server");
        });

        if (hasNogui || hasServerLaunchTarget) {
            return true;
        }

        // No client markers AND no clear server markers either — genuinely
        // ambiguous (e.g. a bare `java -cp ... SomeMainClass` test harness).
        // Default to client behavior, the path that's already tested.
        return false;
    }

    private static boolean containsAny(List<String> tokens, String... needles) {
        for (String t : tokens) {
            String lower = t.toLowerCase(Locale.ROOT);
            for (String n : needles) {
                if (lower.contains(n)) return true;
            }
        }
        return false;
    }

    private static List<String> commandTokens() {
        try {
            String command = System.getProperty("sun.java.command", "");
            if (command == null || command.isBlank()) return null;
            return Arrays.asList(command.trim().split("\\s+"));
        } catch (Throwable t) {
            return null;
        }
    }
}
