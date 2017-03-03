package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * This class as designed, is not supposed to be shared among multiple computers.
 */
public class JCloudsLauncher extends DelegatingComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(JCloudsLauncher.class.getName());

    public JCloudsLauncher(@Nonnull ComputerLauncher launcher) {
        super(launcher);
    }

    @Override
    public void launch(@Nonnull SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        JCloudsSlave node = (JCloudsSlave) computer.getNode();
        long timeout = node.getCreatedTime() + node.getSlaveOptions().getStartTimeout();
        do {
            launcher(computer).launch(computer, listener);
            if (computer.isOnline()) return;

            listener.getLogger().println("Launcher failed to bring the node online. Retrying ...");

            Thread.sleep(2000);
        } while (System.currentTimeMillis() < timeout);

        listener.getLogger().println("Launcher failed to bring the node online within timeout.");
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        try {
            launcher(computer).afterDisconnect(computer, listener);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to create launcher", e);
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        try {
            launcher(computer).beforeDisconnect(computer, listener);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to create launcher", e);
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }

    @SuppressFBWarnings({
            "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            "Since 2.1, tha launcher is injected in constructor but we need this as a fallback for the slaves that survived the upgrade."
    })
    private ComputerLauncher launcher(SlaveComputer computer) throws IOException {
        if (launcher != null) return launcher;
        //return null;

        final JCloudsSlave slave = (JCloudsSlave) computer.getNode();
        // Return something harmless to prevent NPE
        if (slave==null) return launcher = new JNLPLauncher();
        return launcher = slave.getSlaveType().createLauncher(slave);
    }
}
