package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.OfflineCause;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

@Extension @Restricted(NoExternalUse.class)
public final class JCloudsCleanupThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(JCloudsCleanupThread.class.getName());

    public JCloudsCleanupThread() {
        super("OpenStack slave cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN * 5;
    }

    @Override
    public void execute(TaskListener listener) {
        final ImmutableList.Builder<ListenableFuture<?>> deletedNodesBuilder = ImmutableList.builder();
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting);

        for (final Computer c : Jenkins.getActiveInstance().getComputers()) {
            if (c instanceof JCloudsComputer) {
                final JCloudsComputer comp = (JCloudsComputer) c;

                if (!c.isIdle()) continue;

                final OfflineCause offlineCause = comp.getOfflineCause();
                if (comp.isPendingDelete() || offlineCause instanceof DiskSpaceMonitorDescriptor.DiskSpace) {
                    ListenableFuture<?> f = executor.submit(new Runnable() {
                        public void run() {
                            LOGGER.log(Level.INFO, "Deleting pending node " + comp.getName() + ". Reason: " + offlineCause.toString());
                            try {
                                comp.deleteSlave();
                            } catch (IOException|InterruptedException e) {
                                LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + c.getName(), e);
                            } catch (Throwable e) {
                                // The fancy futures stuff ignores failures silently
                                LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + c.getName(), e);
                            }
                        }
                    });
                    deletedNodesBuilder.add(f);
                }
            }
        }

        Futures.getUnchecked(Futures.successfulAsList(deletedNodesBuilder.build()));
    }
}
