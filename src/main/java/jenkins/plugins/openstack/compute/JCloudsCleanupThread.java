package jenkins.plugins.openstack.compute;

import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.Cloud;
import hudson.slaves.OfflineCause;
import jenkins.model.CauseOfInterruption;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.api.exceptions.StatusCode;
import org.openstack4j.model.compute.Server;

import javax.annotation.Nonnull;

/**
 * Periodically ensure Jenkins and resources it manages in OpenStacks are not leaked.
 *
 * Currently it ensures:
 *
 * - Node pending deletion get termionated with their servers
 * - Servers that are running longer than declared are terminated.
 * - Nodes with server missing are terminated.
 */
@Extension @Restricted(NoExternalUse.class)
public final class JCloudsCleanupThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(JCloudsCleanupThread.class.getName());

    private transient @Nonnull ListMultimap<String, String> stillFips = ArrayListMultimap.create();

    private Object readResolve() throws ObjectStreamException {
        stillFips = ArrayListMultimap.create();
        return this;
    }

    public JCloudsCleanupThread() {
        super("OpenStack slave cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN * 10;
    }

    @Override
    public void execute(TaskListener listener) {
        @Nonnull List<JCloudsComputer> running = terminateNodesPendingDeletion();

        @Nonnull HashMap<String, List<Server>> runningServers = destroyServersOutOfScope();

        terminatesNodesWithoutServers(running, runningServers);

        cleanOrphanedFips();
    }

    private void cleanOrphanedFips() {
        for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
            List<String> cloudStillFips = getStillFipsForCloud(cloud);

            List<String> leaked = new ArrayList<>(cloud.getOpenstack().getFreeFipIds());
            List<String> freed = new ArrayList<>(leaked);
            leaked.retainAll(cloudStillFips); // Free on 2 checks
            freed.removeAll(leaked); // Just freed

            synchronized (stillFips) {
                cloudStillFips.clear();
                cloudStillFips.addAll(freed);
            }

            for (String fip : leaked) {
                try {
                    cloud.getOpenstack().destroyFip(fip);
                } catch (ClientResponseException ex) {
                    // The tenant is probably reusing pre-allocated FIPs without permission to (de)allocate new.
                    // https://github.com/jenkinsci/openstack-cloud-plugin/issues/66#issuecomment-207296059
                    if (ex.getStatusCode() == StatusCode.FORBIDDEN) {
                        continue;
                    }
                    LOGGER.log(Level.WARNING, "Unable to release leaked floating IP", ex);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Unable to release leaked floating IP", ex);
                }
            }
        }
    }

    private List<String> getStillFipsForCloud(JCloudsCloud cloud) {
        synchronized (stillFips) {
            return stillFips.get(cloud.name);
        }
    }

    private @Nonnull List<JCloudsComputer> terminateNodesPendingDeletion() {
        ArrayList<JCloudsComputer> runningNodes = new ArrayList<>();
        for (final Computer c : Jenkins.getActiveInstance().getComputers()) {
            if (c instanceof JCloudsComputer) {
                final JCloudsComputer comp = (JCloudsComputer) c;

                if (!c.isIdle()) {
                    runningNodes.add(comp);
                    continue;
                }

                final OfflineCause offlineCause = comp.getFatalOfflineCause();
                if (comp.isPendingDelete() || offlineCause != null) {
                    LOGGER.log(Level.INFO, "Deleting pending node " + comp.getName() + ". Reason: " + comp.getOfflineCause());
                    deleteComputer(comp);
                } else {
                    runningNodes.add(comp);
                }
            }
        }

        return runningNodes;
    }

    private void deleteComputer(JCloudsComputer comp) {
        try {
            comp.deleteSlave();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + comp.getName(), e);
        }
    }

    private void deleteComputer(JCloudsComputer comp, CauseOfInterruption coi) {
        try {
            for (Executor e : comp.getExecutors()) {
                e.interrupt(Result.ABORTED, coi);
            }
            comp.deleteSlave();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + comp.getName(), e);
        }
    }

    private @Nonnull HashMap<String, List<Server>> destroyServersOutOfScope() {
        HashMap<String, List<Server>> runningServers = new HashMap<>();
        for (Cloud cloud : Jenkins.getActiveInstance().clouds) {
            if (cloud instanceof JCloudsCloud) {
                JCloudsCloud jc = (JCloudsCloud) cloud;
                runningServers.put(jc.name, new ArrayList<Server>());
                List<Server> servers = jc.getOpenstack().getRunningNodes();
                for (Server server : servers) {
                    ServerScope scope = ServerScope.extract(server);
                    if (scope.isOutOfScope(server)) {
                        LOGGER.info("Server " + server.getName() + " run out of its scope " + scope + ". Terminating: " + server);
                        AsyncResourceDisposer.get().dispose(new DestroyMachine(cloud.name, server.getId()));
                    } else {
                        runningServers.get(jc.name).add(server);
                    }
                }
            }
        }

        return runningServers;
    }

    private void terminatesNodesWithoutServers(
            @Nonnull List<JCloudsComputer> running,
            @Nonnull HashMap<String, List<Server>> runningServers
    ) {
        next_node: for (JCloudsComputer computer: running) {
            ProvisioningActivity.Id id = computer.getId();
            if (id == null) continue;

            for (Server server : runningServers.get(id.getCloudName())) {
                if (server.getName().equals(computer.getName())) continue next_node;
            }

            String msg = "No OpenStack server running for computer " + computer.getName() + ". Terminating.";
            LOGGER.warning(msg);
            deleteComputer(computer, new MessageInterruption(msg));
        }
    }

    private static class MessageInterruption extends CauseOfInterruption {
        private final String msg;

        private MessageInterruption(String msg) {
            this.msg = msg;
        }

        @Override public String getShortDescription() {
            return msg;
        }
    }
}
