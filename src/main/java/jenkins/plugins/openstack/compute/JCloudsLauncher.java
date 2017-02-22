package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        launcher(computer).launch(computer, listener);

        JCloudsComputer jcloudsComputer = (JCloudsComputer) computer;
        JCloudsSlave jcloudsSlave = jcloudsComputer.getNode();
        Integer launchTimeout = jcloudsSlave.getSlaveOptions().getStartTimeout();
        long startMoment = System.currentTimeMillis();
        String timeoutMessage = String.format("Failed to connect to node %s within timeout (%d ms).", computer.getName(), launchTimeout);

        while (computer.isOffline()) {
            LOGGER.fine(String.format("Waiting for node %s to launch", computer.getDisplayName()));
            Thread.sleep(10000);  // wait 10 seconds before retrying connection
            Throwable lastError = null;
            Future<?> connectionActivity = computer.connect(true);
            try {
                connectionActivity.get(launchTimeout, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | IllegalStateException e) {
                lastError = e.getCause() == null ? e : e.getCause();
                LOGGER.log(Level.FINE, "Error while launching node, retrying: " + computer.getName(), lastError);
                // Retry
            } catch (TimeoutException e) {
                LOGGER.log(Level.WARNING, timeoutMessage, e);
                break;  // Stop trying to connect to node
            }

            if ((System.currentTimeMillis() - startMoment) > launchTimeout) {
                LOGGER.warning(timeoutMessage);
                break;  // Stop trying to connect to node
            }
        }
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
