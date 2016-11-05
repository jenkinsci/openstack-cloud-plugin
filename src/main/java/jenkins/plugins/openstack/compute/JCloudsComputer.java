package jenkins.plugins.openstack.compute;

import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.SlaveComputer;
import hudson.slaves.OfflineCause.SimpleOfflineCause;

import java.io.IOException;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;

/**
 * OpenStack version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());
    private final ProvisioningActivity.Id provisioningId;

    public JCloudsComputer(JCloudsSlave slave) {
        super(slave);
        this.provisioningId = slave.getId();
    }

    @Override
    public @CheckForNull JCloudsSlave getNode() {
        return super.getNode();
    }

    @Override
    public @Nonnull ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    private final Object pendingDeleteLock = new Object();

    /**
     * Flag the slave to be collected asynchronously.
     *
     * @return Old value.
     */
    public boolean setPendingDelete(boolean newVal) {
        synchronized (pendingDeleteLock) {
            boolean is = isPendingDelete();
            if (is == newVal) return is;

            LOGGER.info("Setting " + getName() + " pending delete status to " + newVal);
            setTemporarilyOffline(newVal, newVal ? PENDING_TERMINATION : null);
            return is;
        }
    }

    /**
     * Is slave pending termination.
     */
    public boolean isPendingDelete() {
        // No need  to synchronize reading as offlineCause is volatile
        return offlineCause instanceof PendingTermination;
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override @Restricted(NoExternalUse.class)
    public HttpResponse doDoDelete() throws IOException {
        boolean isAlready = setPendingDelete(true);
        if (!isAlready) {
            JCloudsSlave slave = getNode();
            if (slave == null) {
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
        LOGGER.info("Deleting slave " + getName());
        JCloudsSlave slave = getNode();

        // Slave already deleted
        if (slave == null) {
            LOGGER.info("Skipping, computer is gone already: " + getName());
            return;
        }

        VirtualChannel channel = slave.getChannel();
        if (channel != null) {
            channel.close();
        }
        slave.terminate();
        Jenkins.getActiveInstance().removeNode(slave);
        LOGGER.info("Deleted slave " + getName());
    }

    // Singleton
    private static final PendingTermination PENDING_TERMINATION = new PendingTermination();

    private static final class PendingTermination extends SimpleOfflineCause {

        protected PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}
