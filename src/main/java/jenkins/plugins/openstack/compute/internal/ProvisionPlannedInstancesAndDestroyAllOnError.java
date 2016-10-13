package jenkins.plugins.openstack.compute.internal;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.openstack4j.model.compute.Server;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import hudson.Functions;
import hudson.model.TaskListener;

public class ProvisionPlannedInstancesAndDestroyAllOnError implements Function<Iterable<NodePlan>, Iterable<RunningNode>> {
    private final ListeningExecutorService executor;
    private final TaskListener listener;
    private final Function<Iterable<RunningNode>, Void> terminateNodes;

    public ProvisionPlannedInstancesAndDestroyAllOnError(ListeningExecutorService executor, TaskListener listener, Function<Iterable<RunningNode>, Void> terminateNodes) {
        this.executor = executor;
        this.listener = listener;
        this.terminateNodes = terminateNodes;
    }

    public Iterable<RunningNode> apply(Iterable<NodePlan> nodePlans) {
        final ImmutableList.Builder<RunningNode> cloudTemplateNodeBuilder = ImmutableList.<RunningNode>builder();

        final ImmutableList.Builder<ListenableFuture<Server>> plannedInstancesBuilder = ImmutableList.<ListenableFuture<Server>>builder();

        final AtomicInteger failedLaunches = new AtomicInteger();

        for (final NodePlan nodePlan : nodePlans) {
            for (int i = 0; i < nodePlan.getCount(); i++) {
                final int index = i;
                listener.getLogger().printf(
                        "Queuing cloud instance: #%d %d, %s %s%n",
                        index, nodePlan.getCount(), nodePlan.getCloudName(), nodePlan.getTemplateName()
                );

                ListenableFuture<Server> provisionTemplate = executor.submit(new RetrySupplierOnFailure(nodePlan.getNodeSupplier(), listener));

                Futures.addCallback(provisionTemplate, new FutureCallback<Server>() {
                    public void onSuccess(Server result) {
                        if (result != null) {
                            synchronized (cloudTemplateNodeBuilder) {
                                // Builder in not threadsafec
                                cloudTemplateNodeBuilder.add(new RunningNode(nodePlan.getCloudName(), nodePlan.getTemplateName(), result));
                            }
                        } else {
                            failedLaunches.incrementAndGet();
                        }
                    }

                    public void onFailure(Throwable t) {
                        failedLaunches.incrementAndGet();
                        listener.error(
                                "Error while launching instance: #%d %d, %s %s:%n%s%n",
                                index, nodePlan.getCount(), nodePlan.getCloudName(), nodePlan.getTemplateName(), Functions.printThrowable(t)
                        );
                    }
                });

                plannedInstancesBuilder.add(provisionTemplate);
            }
        }

        // block until all complete
        List<Server> nodesActuallyLaunched = Futures.getUnchecked(Futures.successfulAsList(plannedInstancesBuilder.build()));

        final ImmutableList<RunningNode> cloudTemplateNodes = cloudTemplateNodeBuilder.build();

        if (failedLaunches.get() > 0) {
            terminateNodes.apply(cloudTemplateNodes);
            throw new IllegalStateException("One or more instances failed to launch.");
        }

        assert cloudTemplateNodes.size() == nodesActuallyLaunched.size() : String.format(
                "expected nodes from callbacks to be the same count as those from the list of futures!%n" + "fromCallbacks:%s%nfromFutures%s%n",
                cloudTemplateNodes, nodesActuallyLaunched);

        return cloudTemplateNodes;
    }
}
