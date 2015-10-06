package jenkins.plugins.openstack.compute.internal;

import org.openstack4j.model.compute.Server;

import com.google.common.base.Supplier;

public class NodePlan {
    private final String cloudName;
    private final String templateName;
    private final int count;
    private final Supplier<Server> nodeSupplier;

    public NodePlan(String cloud, String template, int count, Supplier<Server> nodeSupplier) {
        this.cloudName = cloud;
        this.templateName = template;
        this.count = count;
        this.nodeSupplier = nodeSupplier;
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getTemplateName() {
        return templateName;
    }

    public int getCount() {
        return count;
    }

    public Supplier<Server> getNodeSupplier() {
        return nodeSupplier;
    }
}
