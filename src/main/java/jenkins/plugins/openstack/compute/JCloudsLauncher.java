package jenkins.plugins.openstack.compute;

import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.plugins.sshslaves.SSHLauncher;

import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;

import javax.annotation.CheckForNull;

/**
 * The launcher that launches the jenkins slave.jar on the Slave.
 *
 * @author Vijay Kiran
 */
public class JCloudsLauncher extends ComputerLauncher {

    private final @CheckForNull String publicAddress;

    public JCloudsLauncher(@CheckForNull String publicAddress) {
        this.publicAddress = publicAddress;
    }

    /**
     * Launch the Jenkins Slave on the SlaveComputer.
     */
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {

        final JCloudsSlave slave = (JCloudsSlave) computer.getNode();

        if (publicAddress == null) {
            throw new IOException("The slave is likely deleted");
        }
        if ("0.0.0.0".equals(publicAddress)) {
            throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
        }

        if (slave.getSlaveType() == JCloudsCloud.SlaveType.SSH) {
            SSHLauncher launcher = new SSHLauncher(publicAddress, 22, slave.getCredentialsId(), slave.getJvmOptions(), null, "", "", Integer.valueOf(0), null, null);
            launcher.launch(computer, listener);
        } else if (slave.getSlaveType() == JCloudsCloud.SlaveType.JNLP) {
            JNLPLauncher launcher = new JNLPLauncher();
            launcher.launch(computer, listener);
        } else {
            throw new IOException(MessageFormat.format("Unknown slave type: {0}", slave.getSlaveType()));
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
