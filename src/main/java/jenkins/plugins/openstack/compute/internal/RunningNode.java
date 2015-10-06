package jenkins.plugins.openstack.compute.internal;

import org.openstack4j.model.compute.Server;

public class RunningNode {
    private final String cloud;
    private final String template;
    private final Server node;

    public RunningNode(String cloud, String template, Server node) {
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

    public Server getNode() {
        return node;
    }
}
