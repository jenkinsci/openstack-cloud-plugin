package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * This class as designed, is not supposed to be shared among multiple computers.
 */
public class JCloudsLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(JCloudsLauncher.class.getName());

    /*package for testing*/ /*almost final*/ @Nonnull ComputerLauncher launcher;

    public JCloudsLauncher(@Nonnull ComputerLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        launcher(computer).launch(computer, listener);
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
            launcher(computer).afterDisconnect(computer, listener);
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
        return launcher = slave.getSlaveType().createLauncher(slave);
    }
}
