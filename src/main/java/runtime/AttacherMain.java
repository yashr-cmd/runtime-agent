package runtime;

import java.lang.management.ManagementFactory;

public class AttacherMain {

    public static boolean selfAttach(String agentJarPath) {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("[AttacherMain] Self-attaching to own PID: " + pid);
        try {
            Class<?> vmClass = loadVmClass();
            if (vmClass == null) {
                System.err.println("[AttacherMain] com.sun.tools.attach.VirtualMachine not accessible. " +
                        "Add --add-modules jdk.attach to JVM args if on Java 9+.");
                return false;
            }
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            vmClass.getMethod("loadAgent", String.class).invoke(vm, agentJarPath);
            vmClass.getMethod("detach").invoke(vm);
            System.out.println("[AttacherMain] Self-attach successful.");
            return true;
        } catch (Exception e) {
            System.err.println("[AttacherMain] Self-attach failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    public static boolean attachNativePath(String pid, String nativeLibPath, String options) {
        System.out.println("[AttacherMain] Attaching native library to PID " + pid + ": " + nativeLibPath);
        try {
            Class<?> vmClass = loadVmClass();
            if (vmClass == null) {
                System.err.println("[AttacherMain] com.sun.tools.attach.VirtualMachine not accessible. " +
                        "Add --add-modules jdk.attach to JVM args if on Java 9+.");
                return false;
            }
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            vmClass.getMethod("loadAgentPath", String.class, String.class)
                    .invoke(vm, nativeLibPath, options == null ? "" : options);
            vmClass.getMethod("detach").invoke(vm);
            System.out.println("[AttacherMain] Native library attached successfully.");
            return true;
        } catch (Exception e) {
            System.err.println("[AttacherMain] Native attach failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("[AttacherMain] Usage: <pid|self|nativepath> <agent.jar_path | pid> [nativeLibPath] [options]");
            System.exit(1);
        }

        String pid      = args[0];
        String agentPath = args[1];

        if (pid.equals("self")) {
            if (!selfAttach(agentPath)) System.exit(1);
            return;
        }

        if (pid.equals("nativepath")) {
            // usage: nativepath <targetPid> <nativeLibPath> [options]
            if (args.length < 3) {
                System.err.println("[AttacherMain] Usage: nativepath <pid> <nativeLibPath> [options]");
                System.exit(1);
                return;
            }
            String targetPid = args[1];
            String libPath = args[2];
            String options = args.length > 3 ? args[3] : "";
            if (!attachNativePath(targetPid, libPath, options)) System.exit(1);
            return;
        }

        try {
            Class<?> vmClass = loadVmClass();
            if (vmClass == null) {
                System.err.println("[AttacherMain] jdk.attach module not accessible.");
                System.exit(1);
            }
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            vmClass.getMethod("loadAgent", String.class).invoke(vm, agentPath);
            vmClass.getMethod("detach").invoke(vm);
        } catch (Exception e) {
            System.err.println("[AttacherMain] Failed to attach agent:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Class<?> loadVmClass() {
        try {
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}