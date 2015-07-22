package jenkins.plugins.openstack.compute.internal;

import org.jclouds.compute.domain.NodeMetadata;

import shaded.com.google.common.base.Supplier;

public class NodePlan {
    private final String cloudName;
    private final String templateName;
    private final int count;
    private final Supplier<NodeMetadata> nodeSupplier;
    private final int retryTime;

    public NodePlan(String cloud, String template, int count, Supplier<NodeMetadata> nodeSupplier, int retryTime) {
        this.cloudName = cloud;
        this.templateName = template;
        this.count = count;
        this.nodeSupplier = nodeSupplier;
        this.retryTime = retryTime;
    }

    public String getCloudName() {
        return cloudName;
    }

    public int getRetryTime() {
        return retryTime;
    }

    public String getTemplateName() {
        return templateName;
    }

    public int getCount() {
        return count;
    }

    public Supplier<NodeMetadata> getNodeSupplier() {
        return nodeSupplier;
    }
}
