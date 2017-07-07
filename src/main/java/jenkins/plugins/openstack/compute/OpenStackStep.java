package jenkins.plugins.openstack.compute;

import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * Returns the a POJO, Wrapping a org.openstack4j.model.compute.Server object.
 *
 * Usage is as:
 *
 * <pre>
 * node {
 *     def x = openstack cloud: 'mitaka', template: 'CentOS-7'
 * }
 * </pre>
 *
 */
public class OpenStackStep extends Step {

    private @Nonnull String cloud = "";
    private @Nonnull String template = "";
    private @Nonnull String scope = "run";
    private @Nonnull String run = "";

    @DataBoundConstructor
    public OpenStackStep() {
    }

    @DataBoundSetter
    public void setCloud(String cloud) {
        this.cloud = cloud;
    }

    @DataBoundSetter
    public void settemplate(String template) {
        this.template = template;
    }

    @DataBoundSetter
    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getCloud() {
        return cloud;
    }

    public String getTemplate() {
        return template;
    }

    public String getScope() {
        return scope;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        this.run = context.get(Run.class).getFullDisplayName().replace(" #", ":");
        return new OpenStackStep.Execution( this, context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "openstackMachine";
        }

        @Override
        public String getDisplayName() {
            return "Cloud instances provisioning";
        }

        public ListBoxModel doFillCloudItems() {
            ListBoxModel r = new ListBoxModel();
            r.add("", "");
            Jenkins.CloudList clouds = jenkins.model.Jenkins.getActiveInstance().clouds;
            for (Cloud cloud: clouds) {
                if (cloud instanceof JCloudsCloud) {
                    r.add(cloud.getDisplayName(), cloud.getDisplayName());
                }
            }
            return r;
        }

        public ListBoxModel doFillTemplateItems(@QueryParameter String cloud) {
            cloud = Util.fixEmpty(cloud);
            ListBoxModel r = new ListBoxModel();
            for (Cloud cl : jenkins.model.Jenkins.getActiveInstance().clouds) {
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
        private final String cloud;
        private final String template;
        private final String scope;


        Execution(OpenStackStep step, StepContext context) {
            super(context);
            this.cloud = step.cloud;
            this.template = step.template;
            if ("run".equals(step.scope)) {
                this.scope = "run:" + step.run;
            } else if ("unlimited".equals(step.scope)) {
                this.scope = "unlimited:run";
            } else {
                this.scope = step.scope;
            }
        }

        @Override
        protected SimplifiedServer run() throws Exception {
            return new SimplifiedServer(this.cloud, this.template, this.scope);
        }
    }
}
