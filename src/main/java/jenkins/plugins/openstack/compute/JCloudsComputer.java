package jenkins.plugins.openstack.compute;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.SlaveComputer;
import hudson.slaves.OfflineCause.SimpleOfflineCause;

import java.io.IOException;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;

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

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Flag the slave to be collected asynchronously.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        if (!(getOfflineCause() instanceof PendingTermination)) {
            setTemporarilyOffline(true, new PendingTermination());
            JCloudsSlave slave = getNode();
            if (slave != null) {
                slave.setPendingDelete(true);
            } else {
                super.doDoDelete();
            }
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

    private static final class PendingTermination extends SimpleOfflineCause {

        protected PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}
