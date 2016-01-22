package jenkins.plugins.openstack.compute;

import java.util.Arrays;

import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.internal.RunningNode;
import jenkins.plugins.openstack.compute.internal.TerminateNodes;

import org.junit.Rule;
import org.junit.Test;

import static org.mockito.Mockito.*;
import org.openstack4j.model.compute.Server;


import hudson.model.TaskListener;

public class TerminateNodesTest {

    @Rule public PluginTestRule j = new PluginTestRule();

    @Test
    public void destroy() {
        JCloudsCloud cloud = j.dummyCloud();

        Openstack os = cloud.getOpenstack();

        RunningNode keep = new RunningNode("openstack", "keep", mock(Server.class));
        when(keep.getNode().getId()).thenReturn("keep");
        RunningNode terminate = new RunningNode("openstack", "terminate", mock(Server.class));
        when(terminate.getNode().getId()).thenReturn("terminate");

        new TerminateNodes(TaskListener.NULL).apply(Arrays.asList(terminate));

        verify(os).destroyServer(terminate.getNode());
        verifyNoMoreInteractions(os);
    }
}
