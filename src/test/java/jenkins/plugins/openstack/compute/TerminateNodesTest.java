package jenkins.plugins.openstack.compute;

import java.util.List;
import java.util.concurrent.ExecutionException;

import jenkins.plugins.openstack.compute.internal.RunningNode;
import jenkins.plugins.openstack.compute.internal.TerminateNodes;
import junit.framework.TestCase;

import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class TerminateNodesTest extends TestCase {

    private ComputeService compute;

    @Override
    protected void setUp() throws Exception {
        compute = ContextBuilder.newBuilder("stub").buildView(ComputeServiceContext.class).getComputeService();
    }

    private TerminateNodes newTerminateNodes(ComputeService compute) {
        LoadingCache<String, ComputeService> cache = CacheBuilder.newBuilder().build(
                CacheLoader.<String, ComputeService>from(Functions.forMap(ImmutableMap.of("stub", compute))));
        return new TerminateNodes(Logger.NULL, cache);
    }

    public void testDestroyOnlyDestroysNodesInQuestion() throws InterruptedException, ExecutionException, RunNodesException {

        List<NodeMetadata> nodes = ImmutableList.copyOf(compute.createNodesInGroup("destroy", 10));
        List<List<NodeMetadata>> split = Lists.partition(nodes, 5);

        Iterable<RunningNode> runningNodesToDestroy = Iterables.transform(split.get(0), new Function<NodeMetadata, RunningNode>() {

            public RunningNode apply(NodeMetadata input) {
                return new RunningNode("stub", "template", input);
            }

        });

        newTerminateNodes(compute).apply(runningNodesToDestroy);

        for (NodeMetadata node : split.get(0))
            assertEquals(null, compute.getNodeMetadata(node.getId()));
        for (NodeMetadata node : split.get(1))
            assertEquals(NodeMetadata.Status.RUNNING, compute.getNodeMetadata(node.getId()).getStatus());

    }

    @Override
    protected void tearDown() throws Exception {
        compute.getContext().close();
    }
}
