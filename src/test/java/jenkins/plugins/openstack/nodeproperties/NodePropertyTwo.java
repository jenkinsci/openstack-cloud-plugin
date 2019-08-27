package jenkins.plugins.openstack.nodeproperties;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.slaves.NodePropertyDescriptor;

/**
 * Node property used for test purposes.
 */
public class NodePropertyTwo extends AbstractNodeProperty {
    @DataBoundConstructor
    public NodePropertyTwo() {
    }

    private static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Override
    public NodePropertyDescriptor getDescriptor() {
        return DESCRIPTOR;
    }


    @Extension
    @Symbol("nodePropertyTwo")
    public static class DescriptorImpl extends AbstractNodeProperty.DescriptorBase {
        public DescriptorImpl() {
            super(NodePropertyTwo.class);
        }

        @Override
        public String getDisplayName() {
            return "Dummy node property P2";
        }
    }
}
