package jenkins.plugins.openstack.compute;

import hudson.AbortException;
import hudson.Extension;
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

import javax.annotation.Nonnull;

import jenkins.plugins.openstack.compute.internal.NodePlan;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.internal.ProvisionPlannedInstancesAndDestroyAllOnError;
import jenkins.plugins.openstack.compute.internal.RunningNode;
import jenkins.plugins.openstack.compute.internal.TerminateNodes;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.model.compute.Server;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
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

    //
    // convert Jenkins staticy stuff into pojos; performing as little critical stuff here as
    // possible, as this method is very hard to test due to static usage, etc.
    //
    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener) {

        // eagerly lookup node supplier so that errors occur before we attempt to provision things
        Iterable<NodePlan> nodePlans = Iterables.transform(instancesToRun, new Function<InstancesToRun, NodePlan>() {

            @SuppressWarnings("unchecked")
            public NodePlan apply(InstancesToRun instance) {
                String cloudName = instance.cloudName;
                String templateName = Util.replaceMacro(instance.getActualTemplateName(), build.getBuildVariableResolver());
                JCloudsCloud cloud = JCloudsCloud.getByName(cloudName);
                JCloudsSlaveTemplate template = cloud.getTemplate(templateName);
                if (template == null) throw new IllegalArgumentException("No such template " + templateName);
                Supplier<Server> nodeSupplier = new ServerSupplier(cloud, template);
                return new NodePlan(cloudName, templateName, instance.count, nodeSupplier);
            }
        });

        final TerminateNodes terminateNodes = new TerminateNodes(listener);

        ProvisionPlannedInstancesAndDestroyAllOnError provisioner = new ProvisionPlannedInstancesAndDestroyAllOnError(
                MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting), listener, terminateNodes);

        final Iterable<RunningNode> runningNode = provisioner.apply(nodePlans);

        final String ipsString = getIpsString(runningNode);
        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.put("JCLOUDS_IPS", ipsString);
            }

            @Override
            public boolean tearDown(AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
                terminateNodes.apply(runningNode);
                return true;
            }
        };
    }

    private @Nonnull String getIpsString(final Iterable<RunningNode> runningNodes) {
        final List<String> ips = new ArrayList<>(instancesToRun.size());
        for (RunningNode node : runningNodes) {
            String addr = Openstack.getPublicAddress(node.getNode());
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

    private static final class ServerSupplier implements Supplier<Server> {

        private final @Nonnull JCloudsCloud cloud;
        private final @Nonnull JCloudsSlaveTemplate template;

        private ServerSupplier(@Nonnull JCloudsCloud cloud, @Nonnull JCloudsSlaveTemplate template) {
            this.cloud = cloud;
            this.template = template;
        }

        @Override public Server get() {
            return template.provision(cloud);
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
