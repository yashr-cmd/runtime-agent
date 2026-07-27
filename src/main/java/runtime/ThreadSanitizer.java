package runtime;
public class ThreadSanitizer {

    // Stack frame class name fragments that identify pig2 threads
    private static final String[] PIG2_FRAME_MARKERS = {
            "kakiku.pig2mod.",
            "kakiku.",
    };

    public static void killAll() {
        int killed = 0;
        for (Thread t : getAllThreads()) {
            if (t == Thread.currentThread()) continue;
            if (!t.isAlive()) continue;
            if (isPig2Thread(t)) {
                try {
                    t.interrupt();
                    t.join(50);
                    if (t.isAlive()) {
                        //noinspection deprecation
                        t.stop();
                    }
                    killed++;
                    System.err.println("[Pig2ThreadKiller] Killed pig2 thread: "
                            + t.getName() + " (#" + t.getId() + ")");
                } catch (Throwable e) {
                    System.err.println("[Pig2ThreadKiller] Failed to kill thread "
                            + t.getName() + ": " + e.getMessage());
                }
            }
        }
        if (killed > 0) {
            System.err.println("[Pig2ThreadKiller] Total killed this sweep: " + killed);
        }
    }

    public static boolean isCallerPig2() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            for (String marker : PIG2_FRAME_MARKERS) {
                if (cls.startsWith(marker)) {
                    System.err.println("[Pig2ThreadKiller] Blocked pig2 disconnect() call from: " + cls);
                    return true;
                }
            }
        }
        return false;
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

    private static Thread[] getAllThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] threads = new Thread[root.activeCount() + 64];
        int count = root.enumerate(threads, true);
        Thread[] result = new Thread[count];
        System.arraycopy(threads, 0, result, 0, count);
        return result;
    }
}