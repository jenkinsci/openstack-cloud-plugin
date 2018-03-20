package jenkins.plugins.openstack.compute;

import hudson.model.Computer;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.OfflineCause.SimpleOfflineCause;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * OpenStack version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());
    private final ProvisioningActivity.Id provisioningId;

    /**
     * Get all Openstack computers.
     */
    public static @Nonnull List<JCloudsComputer> getAll() {
        ArrayList<JCloudsComputer> out = new ArrayList<>();
        for (final Computer c : Jenkins.getActiveInstance().getComputers()) {
            if (c instanceof JCloudsComputer) {
                out.add((JCloudsComputer) c);
            }
        }
        return out;
    }

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

    /**
     * Flag the slave to be collected asynchronously.
     */
    public void setPendingDelete(boolean newVal) {
        boolean is = isPendingDelete();
        if (is == newVal) return;

        LOGGER.info("Setting " + getName() + " pending delete status to " + newVal);
        setTemporarilyOffline(newVal, newVal ? PENDING_TERMINATION : null);
    }

    /**
     * Is slave pending termination.
     */
    public boolean isPendingDelete() {
        // No need  to synchronize reading as offlineCause is volatile
        return offlineCause instanceof PendingTermination;
    }

    /**
     * Get computer {@link OfflineCause} provided it is severe enough the computer should be discarded.
     *
     * @return value if should be discarded, null if online or offline with non-fatal cause.
     */
    /*package*/ @CheckForNull OfflineCause getFatalOfflineCause() {
        OfflineCause oc = getOfflineCause();
        return oc instanceof DiskSpaceMonitorDescriptor.DiskSpace || oc instanceof OfflineCause.ChannelTermination
                ? oc
                : null
        ;
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override @Restricted(NoExternalUse.class)
    public HttpResponse doDoDelete() {
        checkPermission(Permission.DELETE);
        try {
            deleteSlave();
            return new HttpRedirect("..");
        } catch (Exception ex) {
            return HttpResponses.error(500, ex);
        }
    }

    @Restricted(NoExternalUse.class)
    public HttpRedirect doScheduleTermination() {
        checkPermission(Permission.DELETE);
        setPendingDelete(true);
        return new HttpRedirect(".");
    }

    /**
     * Delete the slave, terminate the instance.
     */
    /*package*/ void deleteSlave() throws IOException, InterruptedException {
        JCloudsSlave slave = getNode();
        if (slave == null) return; // Slave already deleted

        LOGGER.info("Deleting slave " + getName());
        setAcceptingTasks(false); // Prevent accepting further task while we are shutting down
        try {
            slave.terminate();
            LOGGER.info("Deleted slave " + getName());
        } catch (Throwable ex) {
            setAcceptingTasks(true);
            throw ex;
        }
    }

    // Singleton
    private static final PendingTermination PENDING_TERMINATION = new PendingTermination();

    private static final class PendingTermination extends SimpleOfflineCause {

        private PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}
