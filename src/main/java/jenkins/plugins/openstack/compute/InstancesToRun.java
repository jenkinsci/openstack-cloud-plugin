package jenkins.plugins.openstack.compute;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Restricted(NoExternalUse.class)
public final class InstancesToRun extends AbstractDescribableImpl<InstancesToRun> {
    public final String cloudName;
    public final String templateName;
    public final String manualTemplateName;
    public final int count;

    @DataBoundConstructor
    public InstancesToRun(String cloudName, String templateName, String manualTemplateName, int count) {
        this.cloudName = Util.fixEmptyAndTrim(cloudName);
        this.templateName = Util.fixEmptyAndTrim(templateName);
        this.manualTemplateName = Util.fixEmptyAndTrim(manualTemplateName);
        this.count = count;
    }

    public String getActualTemplateName() {
        if (isUsingManualTemplateName()) {
            return manualTemplateName;
        } else {
            return templateName;
        }
    }

    public boolean isUsingManualTemplateName() {
        if (manualTemplateName == null || manualTemplateName.equals("")) {
            return false;
        } else {
            return true;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<InstancesToRun> {
        @RequirePOST
        public ListBoxModel doFillCloudNameItems() {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            ListBoxModel m = new ListBoxModel();
            for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
                m.add(cloud.name, cloud.name);
            }

            return m;
        }

        @RequirePOST
        public ListBoxModel doFillTemplateNameItems(@QueryParameter String cloudName) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            ListBoxModel m = new ListBoxModel();
            if (Util.fixEmpty(cloudName) != null) {
                JCloudsCloud c = JCloudsCloud.getByName(cloudName);
                for (JCloudsSlaveTemplate t : c.getTemplates()) {
                    m.add(String.format("%s in cloud %s", t.name, cloudName), t.name);
                }
            }
            return m;
        }

        @RequirePOST
        public FormValidation doCheckCount(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
