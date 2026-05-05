package runtime;

import com.sun.tools.attach.VirtualMachine;

public class AttacherMain {
    public static void main(String[] args) throws Exception {
        String pid = args[0];
        String agentPath = args[1];
        VirtualMachine vm = VirtualMachine.attach(pid);
        vm.loadAgent(agentPath);
        vm.detach();
    }
}