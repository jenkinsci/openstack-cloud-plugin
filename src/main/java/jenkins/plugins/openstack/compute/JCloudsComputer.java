package jenkins.plugins.openstack.compute;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.remoting.Channel;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.OfflineCause.SimpleOfflineCause;
import hudson.slaves.RetentionStrategy;
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
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * OpenStack version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());
    private final ProvisioningActivity.Id provisioningId;
    private volatile AtomicInteger used = new AtomicInteger(0);
    private transient long connectedTime;

    /**
     * Get all Openstack computers.
     */
    public static @Nonnull List<JCloudsComputer> getAll() {
        ArrayList<JCloudsComputer> out = new ArrayList<>();
        for (final Computer c : Jenkins.get().getComputers()) {
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
        // PendingTermination has a timestamp attached so cannot use a singleton instance
        setTemporarilyOffline(newVal, newVal ? new PendingTermination(): null);
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

    @Override // Overridden for type safety only
    public JCloudsRetentionStrategy getRetentionStrategy() {
        RetentionStrategy<?> rs = super.getRetentionStrategy();
        if (rs instanceof JCloudsRetentionStrategy) return (JCloudsRetentionStrategy) rs;
        return new JCloudsRetentionStrategy();
    }

    private int getRetentionTime() {
        final JCloudsSlave node = getNode();
        if (node == null) return -1;
        return node.getSlaveOptions().getRetentionTime();
    }

    @Override
    public boolean isAcceptingTasks() {
        // If this is a one-off node (i.e. retentionTime == 0) then
        // reject tasks as soon at the first job is started.
        if (getRetentionTime() == 0 && used.get() > 1) {
            return false;
        }
        return super.isAcceptingTasks();
    }

    /*package*/ int getTasksExecuted() {
        return used.get();
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        checkSlaveAfterTaskCompletion();
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        checkSlaveAfterTaskCompletion();
    }

    private void checkSlaveAfterTaskCompletion() {
        used.incrementAndGet();

        // If the retention time for this computer is zero, this means it
        // should not be re-used: mark the node as "pending delete".
        if (getRetentionTime() == 0 && !(getOfflineCause() instanceof OfflineCause.UserCause)) {
            setPendingDelete(true);
        }
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override @Restricted(NoExternalUse.class)
    @RequirePOST
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
    @RequirePOST
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

        LOGGER.info("Deleting slave " + getName() + " after executing " + getTasksExecuted() + " builds");
        setAcceptingTasks(false); // Prevent accepting further task while we are shutting down
        try {
            slave.terminate();
            LOGGER.info("Deleted slave " + getName());
        } catch (Throwable ex) {
            setAcceptingTasks(true);
            throw ex;
        }
    }


    public void setChannel(Channel channel, OutputStream launchLog, Channel.Listener listener) throws IOException, InterruptedException {
        super.setChannel(channel, launchLog, listener);
        connectedTime = System.currentTimeMillis();
    }

    public long getConnectedTime(){
        return connectedTime;
    }

    public long getIdleSince(){
        long iddleTime = super.getIdleStartMilliseconds();
        if(connectedTime > iddleTime) {
            return connectedTime;
        }
        return iddleTime;
    }


    private static final class PendingTermination extends SimpleOfflineCause {

        private PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}
