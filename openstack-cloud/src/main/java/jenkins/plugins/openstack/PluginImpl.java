package jenkins.plugins.openstack;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * @author Vijay Kiran
 */
@Extension @Deprecated // Usused API
public class PluginImpl extends Plugin implements Describable<PluginImpl> {

    @Override
    public void start() throws Exception {
        load();
    }

    public Descriptor<PluginImpl> getDescriptor() {
        return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    public static PluginImpl get() {
        return Jenkins.getInstance().getPlugin(PluginImpl.class);
    }

    @Extension @Deprecated // Usused API
    public static final class DescriptorImpl extends Descriptor<PluginImpl> {
        @Override
        public String getDisplayName() {
            return "OpenStack Jenkins PluginImpl";
        }
    }
}
