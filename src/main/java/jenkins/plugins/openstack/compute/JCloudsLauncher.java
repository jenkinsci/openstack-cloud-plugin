package jenkins.plugins.openstack.compute;

import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import javax.annotation.Nonnull;
import java.io.IOException;

/*
 * This class as designed, is not supposed to be shared among multiple computers.
 */
public class JCloudsLauncher extends ComputerLauncher {

    /*package for testing*/ final @Nonnull ComputerLauncher launcher;

    public JCloudsLauncher(@Nonnull ComputerLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        launcher.launch(computer, listener);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        launcher.afterDisconnect(computer, listener);
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        launcher.afterDisconnect(computer, listener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
