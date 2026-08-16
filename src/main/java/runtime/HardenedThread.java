package runtime;

public class HardenedThread extends Thread {

    private static final StackTraceElement[] DECOY_STACK = buildDecoyStack();

    public HardenedThread(Runnable target, String name) {
        super(target, name);
    }

    private static StackTraceElement[] buildDecoyStack() {
        StackTraceElement[] decoy = new StackTraceElement[6];
        for (int i = 0; i < decoy.length; i++) {
            decoy[i] = new StackTraceElement("java.lang.Thread", "run", "Thread.java", 840);
        }
        return decoy;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return DECOY_STACK.clone(); // clone so callers can't mutate the shared backing array
    }

    @Override
    public void interrupt() {
    }

    @Override
    public boolean isInterrupted() {
        return false;
    }
}