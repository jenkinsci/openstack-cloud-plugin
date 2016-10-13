package jenkins.plugins.openstack.compute.internal;

import java.util.concurrent.Callable;

import org.openstack4j.model.compute.Server;

import com.google.common.base.Supplier;

import hudson.Functions;
import hudson.model.TaskListener;

class RetrySupplierOnFailure implements Callable<Server> {
    private static final int MAX_ATTEMPTS = 5;
    private final TaskListener listener;
    private final Supplier<Server> supplier;

    RetrySupplierOnFailure(Supplier<Server> supplier, TaskListener listener) {
        this.supplier = supplier;
        this.listener = listener;
    }

    public Server call() throws Exception {
        int attempts = 0;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            try {
                Server n = supplier.get();
                if (n != null) {
                    return n;
                }
            } catch (RuntimeException e) {
                listener.error("Exception creating a node");
                listener.getLogger().println(Functions.printThrowable(e));
                // Something to log the e.getCause() which should be a
                // RunNodesException
            }
        }

        return null;
    }
}
