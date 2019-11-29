package jenkins.plugins.openstack.nodeproperties;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.slaves.NodePropertyDescriptor;

/**
 * Node property used for test purposes.
 */
public class NodePropertyOne extends AbstractNodeProperty {
    @DataBoundConstructor
    public NodePropertyOne() {
    }

    private static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Override
    public NodePropertyDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    @Symbol("nodePropertyOne")
    public static class DescriptorImpl extends AbstractNodeProperty.DescriptorBase {
        public DescriptorImpl() {
            super(NodePropertyOne.class);
        }

        @Override
        public String getDisplayName() {
            return "Dummy node property P1";
        }
    }
}
