package jenkins.plugins.openstack.compute.internal;

import java.util.Collection;

import org.openstack4j.model.compute.Server;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import hudson.model.TaskListener;
import jenkins.plugins.openstack.compute.JCloudsCloud;

import com.google.common.collect.ImmutableMultimap.Builder;

public class TerminateNodes implements Function<Iterable<RunningNode>, Void> {
    private final TaskListener listener;

    public TerminateNodes(TaskListener listener) {
        this.listener = listener;
    }

    public Void apply(Iterable<RunningNode> runningNodes) {
        // Group by cloud to avoid re-authentication
        Builder<String, Server> cloudNodesToDestroyBuilder = ImmutableMultimap.<String, Server>builder();
        for (RunningNode cloudTemplateNode : runningNodes) {
            cloudNodesToDestroyBuilder.put(cloudTemplateNode.getCloudName(), cloudTemplateNode.getNode());
        }
        Multimap<String, Server> cloudNodesToDestroy = cloudNodesToDestroyBuilder.build();

        destroy(cloudNodesToDestroy);
        return null;
    }

    private void destroy(Multimap<String, Server> cloudNodesToDestroy) {
        for (String cloudToDestroy : cloudNodesToDestroy.keySet()) {
            final Collection<Server> nodesToDestroy = cloudNodesToDestroy.get(cloudToDestroy);
            listener.getLogger().println("Destroying nodes: " + nodesToDestroy);
            Openstack os = JCloudsCloud.getByName(cloudToDestroy).getOpenstack();
            for (Server node: nodesToDestroy) {
                os.destroyServer(node);
            }
        }
    }
}
