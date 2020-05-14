package jenkins.plugins.openstack.compute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.slaves.OfflineCause;
import jenkins.model.CauseOfInterruption;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.api.exceptions.StatusCode;
import org.openstack4j.model.compute.Server;

import javax.annotation.Nonnull;

/**
 * Periodically ensure Jenkins and resources it manages in OpenStacks are not leaked.
 *
 * Currently it ensures:
 *
 * - Node pending deletion get terminated with their servers.
 * - Servers that are running longer than declared are terminated.
 * - Nodes with server missing are terminated.
 */
@Extension @Restricted(NoExternalUse.class)
public final class JCloudsCleanupThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(JCloudsCleanupThread.class.getName());

    private final @Nonnull ListMultimap<String, String> stillFips = ArrayListMultimap.create();

    public JCloudsCleanupThread() {
        super("OpenStack slave cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN * 10;
    }

    @Override
    public void execute(TaskListener listener) {
        try {
            terminateNodesPendingDeletion();

            @Nonnull HashMap<JCloudsCloud, List<Server>> runningServers = destroyServersOutOfScope();

            terminatesNodesWithoutServers(runningServers);

            cleanOrphanedFips();
        } catch (JCloudsCloud.LoginFailure ex) {
            LOGGER.log(Level.WARNING, "Unable to authenticate: " + ex.getMessage());
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "Unable to perform the cleanup", ex);
        }
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

    private void terminateNodesPendingDeletion() {
        for (final JCloudsComputer comp : JCloudsComputer.getAll()) {
            if (!comp.isIdle()) continue;

            final OfflineCause offlineCause = comp.getFatalOfflineCause();
            if (comp.isPendingDelete()) {
                LOGGER.log(Level.INFO, "Deleting pending node " + comp.getName() + ". Reason: " + comp.getOfflineCause());
                deleteComputer(comp);
            } else if (offlineCause != null) {
                LOGGER.log(Level.WARNING, "Deleting broken node " + comp.getName() + " (" + getTerminalDiagnosis(comp) + "). Reason: " + comp.getOfflineCause());

                deleteComputer(comp);
            }
        }
    }

    private String getTerminalDiagnosis(JCloudsComputer comp) {
        try {
            JCloudsSlave node = comp.getNode();
            if (node == null) return "Node is gone";

            JCloudsCloud cloud;
            try {
                cloud = JCloudsCloud.getByName(comp.getId().getCloudName());
            } catch (IllegalArgumentException e) {
                return "Cloud no longer configured - cannot get more info";
            }
            Server server;
            try {
                server = cloud.getOpenstack().getServerById(node.getServerId());
            } catch (NoSuchElementException e) {
                return "Server does not exist in OpenStack";
            }
            return server.toString();
            // TODO capturing server log might be useful
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed diagnosing computer failure", ex);
            return "none";
        }
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
            for(Executor e : comp.getOneOffExecutors()){
                e.interrupt(Result.ABORTED, coi);
            }
            comp.deleteSlave();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + comp.getName(), e);
        }
    }

    /**
     * @return Servers not destroyed as they are in scope.
     */
    private @Nonnull HashMap<JCloudsCloud, List<Server>> destroyServersOutOfScope() {
        HashMap<JCloudsCloud, List<Server>> runningServers = new HashMap<>();
        for (JCloudsCloud jc : JCloudsCloud.getClouds()) {
            runningServers.put(jc, new ArrayList<>());
            List<Server> servers = jc.getOpenstack().getRunningNodes();
            for (Server server : servers) {
                ServerScope scope = ServerScope.extract(server);
                if (scope.isOutOfScope(server)) {
                    LOGGER.info("Server " + server.getName() + " run out of its scope " + scope + ". Terminating: " + server);
                    AsyncResourceDisposer.get().dispose(new DestroyMachine(jc.name, server.getId()));
                } else {
                    runningServers.get(jc).add(server);
                }
            }
        }

        return runningServers;
    }

    private void terminatesNodesWithoutServers(@Nonnull HashMap<JCloudsCloud, List<Server>> runningServers) {
        Map<String, JCloudsComputer> jenkinsComputers = new HashMap<>();
        for (JCloudsComputer computer: JCloudsComputer.getAll()) {
            JCloudsSlave node = computer.getNode();
            if (node != null) {
                jenkinsComputers.put(node.getServerId(), computer);
            }
        }

        // Eliminate computers we have servers for
        for (Map.Entry<JCloudsCloud, List<Server>> e: runningServers.entrySet()) {
            for (Server server : e.getValue()) {
                jenkinsComputers.remove(server.getId());
            }
        }

        for (Map.Entry<String, JCloudsComputer> entry : jenkinsComputers.entrySet()) {
            JCloudsComputer computer = entry.getValue();
            String id = entry.getKey();
            String cloudName = computer.getId().getCloudName();
            JCloudsCloud cloud;
            try {
                cloud = JCloudsCloud.getByName(cloudName);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("The cloud " + cloudName + " does not longer exists for " + computer.getName());
                // TODO: we ware unable to perform the double lookup - keeping the node alive. Once we are confident
                // enough in this, we can do the cleanup anyway
                continue;
            }

            try { // Double check server does not exist before interrupting jobs
                Server explicitLookup = cloud.getOpenstack().getServerById(id);
                if (Openstack.isOccupied(explicitLookup)) {
                    LOGGER.severe(getClass().getSimpleName() + " incorrectly detected orphaned computer for " + explicitLookup);
                    continue; // Do not kill it
                }
            } catch (NoSuchElementException expected) {
                // Gone as expected
            }

            String msg = "OpenStack server (" + id + ") is not running for computer " + computer.getName() + ". Terminating!";
            LOGGER.warning(msg);
            deleteComputer(computer, new MessageInterruption(msg));
        }
    }

    @Override protected Level getNormalLoggingLevel() { return Level.OFF; }
    @Override protected Level getSlowLoggingLevel() { return Level.INFO; }

    private static class MessageInterruption extends CauseOfInterruption {
        private static final long serialVersionUID = 7125610351278586647L;

        private final String msg;

        private MessageInterruption(String msg) {
            this.msg = msg;
        }

        @Override public String getShortDescription() {
            return msg;
        }
    }
}
