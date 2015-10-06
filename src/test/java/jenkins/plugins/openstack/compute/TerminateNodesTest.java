package jenkins.plugins.openstack.compute;

import java.util.Arrays;

import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.internal.RunningNode;
import jenkins.plugins.openstack.compute.internal.TerminateNodes;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.mockito.Mockito.*;
import org.openstack4j.model.compute.Server;


import hudson.model.TaskListener;

public class TerminateNodesTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @Test
    public void destroy() {
        JCloudsCloud cloud = spy(new JCloudsCloud("stub", null, null, null, 0, 0, 0, 0, null, null, false));
        j.jenkins.clouds.add(cloud);

        Openstack os = mock(Openstack.class, RETURNS_SMART_NULLS);
        doReturn(os).when(cloud).getOpenstack();

        RunningNode keep = new RunningNode("stub", "keep", mock(Server.class));
        when(keep.getNode().getId()).thenReturn("keep");
        RunningNode terminate = new RunningNode("stub", "terminate", mock(Server.class));
        when(terminate.getNode().getId()).thenReturn("terminate");

        new TerminateNodes(TaskListener.NULL).apply(Arrays.asList(terminate));

        verify(os).destroyServer(terminate.getNode());
        verifyNoMoreInteractions(os);
    }
}
