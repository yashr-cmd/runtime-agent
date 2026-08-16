package runtime;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AgentLog {

    private static final Object LOCK = new Object();
    private static volatile PrintStream fileStream;

    private AgentLog() {}

    private static PrintStream stream() {
        PrintStream fs = fileStream;
        if (fs == null) {
            synchronized (LOCK) {
                if (fileStream == null) {
                    try {
                        Path p = Paths.get(System.getProperty("user.dir", "."), "transfinity-agent.log");
                        fileStream = new PrintStream(Files.newOutputStream(p,
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
                                true, StandardCharsets.UTF_8);
                    } catch (Throwable t) {
                        fileStream = System.out;
                    }
                }
                fs = fileStream;
            }
        }
        return fs;
    }

    public static void log(String msg) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + msg;
        try { System.out.println(line); } catch (Throwable ignored) {}
        try { stream().println(line); } catch (Throwable ignored) {}
    }

    public static void logThrowable(String msg, Throwable t) {
        log(msg);
        if (t == null) return;
        try { t.printStackTrace(System.out); } catch (Throwable ignored) {}
        try { t.printStackTrace(stream()); } catch (Throwable ignored) {}
    }
}