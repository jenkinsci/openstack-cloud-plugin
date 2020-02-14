package jenkins.plugins.openstack.nodeproperties;

import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;

public abstract class AbstractNodeProperty extends NodeProperty<Node> {
    protected AbstractNodeProperty() {
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && getClass() == obj.getClass();
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    public static class DescriptorBase extends NodePropertyDescriptor {
        protected DescriptorBase(Class<? extends AbstractNodeProperty> clazz) {
            super(clazz);
        }

        @Override
        public boolean isApplicableAsGlobal() {
            return true;
        }

        @Override
        public boolean isApplicable(Class<? extends Node> targetType) {
            return true;
        }
    }
}
