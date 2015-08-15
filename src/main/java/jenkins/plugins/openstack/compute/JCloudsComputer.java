package jenkins.plugins.openstack.compute;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

/**
 * JClouds version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 *
 * @author Vijay Kiran
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());

    public JCloudsComputer(JCloudsSlave slave) {
        super(slave);
    }

    public String getInstanceId() {
        return getName();
    }

    @Override
    public JCloudsSlave getNode() {
        return super.getNode();
    }

    public int getRetentionTime() {
        return getNode().getRetentionTime();
    }

    public String getCloudName() {
        return getNode().getCloudName();
    }

    /**
     * Really deletes the slave, by terminating the instance.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        setTemporarilyOffline(true, OfflineCause.create(Messages._DeletedCause()));
        JCloudsSlave slave = getNode();
        if (slave != null) {
            slave.setPendingDelete(true);
        }
        return new HttpRedirect("..");
    }

    /**
     * Delete the slave, terminate the instance. Can be called either by doDoDelete() or from JCloudsRetentionStrategy.
     *
     * @throws InterruptedException
     */
    public void deleteSlave() throws IOException, InterruptedException {
        LOGGER.info("Terminating " + getName() + " slave");
        JCloudsSlave slave = getNode();

        // Slave already deleted
        if (slave == null) return;

        if (slave.getChannel() != null) {
            slave.getChannel().close();
        }
        slave.terminate();
        Jenkins.getInstance().removeNode(slave);
    }
}
