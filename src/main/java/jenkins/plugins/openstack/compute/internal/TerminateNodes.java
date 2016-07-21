package jenkins.plugins.openstack.compute.internal;

import com.google.common.base.Function;

import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;

public class TerminateNodes implements Function<Iterable<RunningNode>, Void> {

    public Void apply(Iterable<RunningNode> runningNodes) {
        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        for (RunningNode rn: runningNodes) {
            disposer.dispose(new DestroyMachine(rn.getCloudName(), rn.getNode().getId()));
        }
        return null;
    }
}
