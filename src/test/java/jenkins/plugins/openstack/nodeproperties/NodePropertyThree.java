package jenkins.plugins.openstack.nodeproperties;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.slaves.NodePropertyDescriptor;

/**
 * Node property used for test purposes.
 */
public class NodePropertyThree extends AbstractNodeProperty {
    @DataBoundConstructor
    public NodePropertyThree() {
    }

    private static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Override
    public NodePropertyDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    @Symbol("nodePropertyThree")
    public static class DescriptorImpl extends AbstractNodeProperty.DescriptorBase {
        public DescriptorImpl() {
            super(NodePropertyThree.class);
        }

        @Override
        public String getDisplayName() {
            return "Dummy node property P3";
        }
    }
}
