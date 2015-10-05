package jenkins.plugins.openstack.compute.internal;

import java.util.concurrent.Callable;

import org.jclouds.compute.domain.NodeMetadata;

import com.google.common.base.Supplier;

import hudson.model.TaskListener;

class RetrySupplierOnException implements Callable<NodeMetadata> {
    private final int MAX_ATTEMPTS = 5;
    private final TaskListener listener;
    private final Supplier<NodeMetadata> supplier;

    RetrySupplierOnException(Supplier<NodeMetadata> supplier, TaskListener listener) {
        this.supplier = supplier;
        this.listener = listener;
    }

    public NodeMetadata call() throws Exception {
        int attempts = 0;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            try {
                NodeMetadata n = supplier.get();
                if (n != null) {
                    return n;
                }
            } catch (RuntimeException e) {
                listener.error("Exception creating a node: " + e.getMessage());
                // Something to log the e.getCause() which should be a
                // RunNodesException
            }
        }

        return null;
    }
}
