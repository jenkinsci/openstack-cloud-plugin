package jenkins.plugins.openstack.compute;

import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;

/*
 * This class as designed, is not supposed to be shared among multiple computers.
 */
public class JCloudsLauncher extends ComputerLauncher {

    /*package for testing*/ ComputerLauncher lastLauncher;

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {

        final JCloudsSlave slave = (JCloudsSlave) computer.getNode();

        lastLauncher = slave.getSlaveType().createLauncher(slave);
        lastLauncher.launch(computer, listener);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        lastLauncher.afterDisconnect(computer, listener);
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        lastLauncher.afterDisconnect(computer, listener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
