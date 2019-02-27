package jenkins.plugins.openstack.compute;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import hudson.Extension;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;

import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.model.compute.Server;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;

public class JCloudsBuildWrapper extends BuildWrapper {
    private final List<InstancesToRun> instancesToRun;

    @DataBoundConstructor
    public JCloudsBuildWrapper(List<InstancesToRun> instancesToRun) {
        this.instancesToRun = instancesToRun;
    }

    @Restricted(NoExternalUse.class) // View
    public List<InstancesToRun> getInstancesToRun() {
        return instancesToRun;
    }

    // convert Jenkins static stuff into pojos; performing as little critical stuff here as
    // possible, as this method is very hard to test due to static usage, etc.
    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener) {
        // TODO check quota here to abort the task if we do not have enough resources

        final ServerScope.Build scope = new ServerScope.Build(build);

        // eagerly lookup node supplier so that errors occur before we attempt to provision things
        Iterable<NodePlan> nodePlans = Iterables.transform(instancesToRun, new Function<InstancesToRun, NodePlan>() {

            @SuppressWarnings("unchecked")
            public NodePlan apply(InstancesToRun instance) {
                JCloudsCloud cloud = JCloudsCloud.getByName(instance.cloudName);
                String templateName = Util.replaceMacro(instance.getActualTemplateName(), build.getBuildVariableResolver());
                JCloudsSlaveTemplate template = cloud.getTemplate(templateName);
                if (template == null) throw new IllegalArgumentException("No such template " + templateName);
                return new NodePlan(cloud, template, instance.count, scope);
            }
        });

        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting);
        final ImmutableList.Builder<RunningNode> cloudTemplateNodeBuilder = ImmutableList.builder();

        final ImmutableList.Builder<ListenableFuture<Server>> plannedInstancesBuilder = ImmutableList.builder();

        final AtomicInteger failedLaunches = new AtomicInteger();

        for (final NodePlan nodePlan : nodePlans) {
            for (int i = 0; i < nodePlan.getCount(); i++) {
                final int index = i;
                listener.getLogger().printf(
                        "Queuing cloud instance: #%d %s %s%n",
                        index, nodePlan.getCloud(), nodePlan.getTemplate()
                );

                ListenableFuture<Server> provisionTemplate = executor.submit(nodePlan.getNodeSupplier());

                Futures.addCallback(provisionTemplate, new FutureCallback<Server>() {
                    public void onSuccess(Server result) {
                        if (result != null) {
                            synchronized (cloudTemplateNodeBuilder) {
                                // Builder in not threadsafe
                                cloudTemplateNodeBuilder.add(new RunningNode(nodePlan.getCloud(), result));
                            }
                        } else {
                            failedLaunches.incrementAndGet();
                        }
                    }

                    public void onFailure(@Nonnull Throwable t) {
                        failedLaunches.incrementAndGet();
                        listener.error(
                                "Error while launching instance: #%d, %s %s:%n%s%n",
                                index, nodePlan.getCloud(), nodePlan.getTemplate(), Functions.printThrowable(t)
                        );
                    }
                });

                plannedInstancesBuilder.add(provisionTemplate);
            }
        }

        // block until all complete
        List<Server> nodesActuallyLaunched = Futures.getUnchecked(Futures.successfulAsList(plannedInstancesBuilder.build()));

        final ImmutableList<RunningNode> runningNode = cloudTemplateNodeBuilder.build();

        if (failedLaunches.get() > 0) {
            terminateNodes(runningNode);
            throw new IllegalStateException("One or more instances failed to launch.");
        }

        assert runningNode.size() == nodesActuallyLaunched.size() : String.format(
                "expected nodes from callbacks to be the same count as those from the list of futures!%n" + "fromCallbacks:%s%nfromFutures%s%n",
                runningNode, nodesActuallyLaunched);


        final String ipsString = getIpsString(runningNode);
        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.put("JCLOUDS_IPS", ipsString);
            }

            @Override
            public boolean tearDown(AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
                terminateNodes(runningNode);
                return true;
            }
        };
    }

    private @Nonnull String getIpsString(final Iterable<RunningNode> runningNodes) {
        final List<String> ips = new ArrayList<>(instancesToRun.size());
        for (RunningNode node : runningNodes) {
            String addr = Openstack.getAccessIpAddress(node.getNode());
            if (addr != null) {
                ips.add(addr);
            } else {
                // TODO this is serious enough to report that to user as the machine is practically inaccessible
                // Putting in empty string not to shift the addresses to make this easier to debug
                ips.add("");
            }
        }

        return Util.join(ips, ",");
    }

    private static void terminateNodes(Iterable<RunningNode> runningNodes) {
        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        for (RunningNode rn: runningNodes) {
            disposer.dispose(new DestroyMachine(rn.getCloudName(), rn.getNode().getId()));
        }
    }

    public static class RunningNode {
        private final String cloud;
        private final Server node;

        RunningNode(String cloud, Server node) {
            this.cloud = cloud;
            this.node = node;
        }

        public String getCloudName() {
            return cloud;
        }

        public Server getNode() {
            return node;
        }
    }

    public static class NodePlan {
        private final JCloudsCloud cloud;
        private final JCloudsSlaveTemplate template;
        private final int count;
        private final ServerScope.Build scope;

        NodePlan(JCloudsCloud cloud, JCloudsSlaveTemplate template, int count, ServerScope.Build scope) {
            this.cloud = cloud;
            this.template = template;
            this.count = count;
            this.scope = scope;
        }

        public String getCloud() {
            return cloud.name;
        }

        public String getTemplate() {
            return template.name;
        }

        public int getCount() {
            return count;
        }

        Callable<Server> getNodeSupplier() {
            final JCloudsCloud cloud1 = cloud;
            final JCloudsSlaveTemplate template1 = template;
            return new Callable<Server>() {
                private final @Nonnull JCloudsCloud cloud = cloud1;
                private final @Nonnull JCloudsSlaveTemplate template = template1;

                @Override
                public Server call() throws Exception {
                    return template.provision(cloud, scope);
                }
            };
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "OpenStack Instance Creation";
        }

        @Override
        public boolean isApplicable(AbstractProject item) {
            return true;
        }
    }
}
