package jenkins.plugins.openstack.compute;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * OpenStack version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());
    private final ProvisioningActivity.Id provisioningId;
    private volatile boolean used;

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
        used = false;
    }

    @Override
    public @CheckForNull JCloudsSlave getNode() {
        return super.getNode();
    }

    /**
     * Gets the metadata settings that were provided to Openstack
     * when the slave was created by the plugin, excluding any empty values.
     * 
     * @return A Map of metadata key to metadata value. This will not be null.
     */
    public Map<String, String> getOpenstackMetaData() {
        final Map<String, String> result = new LinkedHashMap<>();
        final JCloudsSlave node = getNode();
        if (node != null) {
            final Map<String, String> metaData = node.getOpenstackMetaData();
            for (Map.Entry<String, String> entry : metaData.entrySet()) {
                putIfNotNullOrEmpty(result, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Gets most of the Server settings that were provided to Openstack
     * when the slave was created by the plugin.
     * Not all settings are interesting and any that are empty/null are omitted.
     * 
     * @return A Map of metadata key to metadata value. This will not be null.
     */
    public Map<String, String> getOpenstackSlaveOptions() {
        final Map<String, String> result = new LinkedHashMap<>();
        final JCloudsSlave node = getNode();
        if (node != null) {
            final SlaveOptions slaveOptions = node.getSlaveOptions();
            putIfNotNullOrEmpty(result, "ServerId", node.getServerId());
            putIfNotNullOrEmpty(result, "HardwareId", slaveOptions.getHardwareId());
            putIfNotNullOrEmpty(result, "NetworkId", slaveOptions.getNetworkId());
            putIfNotNullOrEmpty(result, "FloatingIpPool", slaveOptions.getFloatingIpPool());
            putIfNotNullOrEmpty(result, "SecurityGroups", slaveOptions.getSecurityGroups());
            putIfNotNullOrEmpty(result, "AvailabilityZone", slaveOptions.getAvailabilityZone());
            putIfNotNullOrEmpty(result, "StartTimeout", slaveOptions.getStartTimeout());
            putIfNotNullOrEmpty(result, "KeyPairName", slaveOptions.getKeyPairName());
            final Object launcherFactory = slaveOptions.getLauncherFactory();
            putIfNotNullOrEmpty(result, "LauncherFactory",
                    launcherFactory == null ? null : launcherFactory.getClass().getSimpleName());
            putIfNotNullOrEmpty(result, "JvmOptions", slaveOptions.getJvmOptions());
        }
        return result;
    }

    private static void putIfNotNullOrEmpty(final Map<String, String> mapToBeAddedTo, final String fieldName,
            final Object fieldValue) {
        if (fieldValue != null) {
            final String valueString = fieldValue.toString();
            if (!valueString.trim().isEmpty()) {
                mapToBeAddedTo.put(fieldName, valueString);
            }
        }
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
        if (isUsed() && getRetentionTime() == 0) {
            return false;
        }
        return super.isAcceptingTasks();
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        used = true;
    }

    /**
     * Has this computer been used to run builds?
     */
    public boolean isUsed() {
        return used;
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
        // If the retention time for this computer is zero, this means it
        // should not be re-used: mark the node as "pending delete".
        if (getRetentionTime() == 0) {
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
