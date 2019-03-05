package jenkins.plugins.openstack.pipeline;

import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.ServerScope;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;

/**
 * Provision auxiliary server not to be connected to Jenkins.
 *
 * Usage is as:
 *
 * <pre>
 * node {
 *     def x = openstackMachine cloud: 'mitaka', template: 'CentOS-7'
 *     def y = openstackMachine cloud: 'mitaka', template: 'CentOS-7', scope: 'unlimited'
 * }
 * </pre>
 */
@Restricted(NoExternalUse.class)
public class OpenStackMachineStep extends Step {

    private @Nonnull String cloud = "";
    private @Nonnull String template = "";
    private @Nonnull String scope = "run";

    @DataBoundConstructor
    public OpenStackMachineStep() {
    }

    @DataBoundSetter
    public void setCloud(@Nonnull String cloud) {
        this.cloud = cloud;
    }

    @DataBoundSetter
    public void setTemplate(@Nonnull String template) {
        this.template = template;
    }

    @DataBoundSetter
    public void setScope(@CheckForNull String scope) {
        if (scope != null) {
            if ("run".equals(scope) || scope.startsWith("unlimited:")) {
                this.scope = scope;
            } else {
                throw new IllegalArgumentException("Invalid scope: " + scope);
            }
        }
    }

    @Nonnull public String getCloud() {
        return cloud;
    }

    @Nonnull public String getTemplate() {
        return template;
    }

    @Nonnull public String getScope() {
        return scope;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        if (scope.equals("run")) {
            scope = new ServerScope.Build(context.get(Run.class)).getValue();
        }
        ServerScope.parse(scope);
        return new OpenStackMachineStep.Execution( this, context);
    }

    @Extension(optional = true)
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "openstackMachine";
        }

        @Override
        public String getDisplayName() {
            return "Cloud instances provisioning";
        }

        @RequirePOST
        public ListBoxModel doFillCloudItems() {
            ListBoxModel r = new ListBoxModel();
            r.add("", "");
            Jenkins.CloudList clouds = jenkins.model.Jenkins.get().clouds;
            for (Cloud cloud: clouds) {
                if (cloud instanceof JCloudsCloud) {
                    r.add(cloud.getDisplayName(), cloud.getDisplayName());
                }
            }
            return r;
        }

        @RequirePOST
        public ListBoxModel doFillTemplateItems(@QueryParameter String cloud) {
            cloud = Util.fixEmpty(cloud);
            ListBoxModel r = new ListBoxModel();
            for (Cloud cl : jenkins.model.Jenkins.get().clouds) {
                if (cl.getDisplayName().equals(cloud) && (cl instanceof JCloudsCloud)) {
                    for (JCloudsSlaveTemplate template : ((JCloudsCloud) cl).getTemplates()) {
                       r.add(template.name);
                    }
                }
            }
            return r;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<SimplifiedServer> {
        private static final long serialVersionUID = -1471755378664792632L;

        private final @Nonnull String cloud;
        private final @Nonnull String template;
        private final @Nonnull String scope;

        Execution(OpenStackMachineStep step, StepContext context) {
            super(context);
            this.cloud = step.cloud;
            this.template = step.template;
            this.scope = step.scope;
        }

        @Override
        protected SimplifiedServer run() throws Exception {
            return new SimplifiedServer(this.cloud, this.template, this.scope);
        }
    }
}
