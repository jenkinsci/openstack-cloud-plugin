package jenkins.plugins.jclouds.compute.internal;

import org.jclouds.compute.domain.NodeMetadata;

public class RunningNode {
    private final String cloud;
    private final String template;
    private final NodeMetadata node;

    public RunningNode(String cloud, String template, NodeMetadata node) {
        this.cloud = cloud;
        this.template = template;
        this.node = node;
    }

    public String getCloudName() {
        return cloud;
    }

    public String getTemplateName() {
        return template;
    }

    public NodeMetadata getNode() {
        return node;
    }
}
