package jenkins.plugins.openstack.compute.internal;

import java.util.Collections;

import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud;

import org.junit.Rule;
import org.junit.Test;

import static org.mockito.Mockito.*;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openstack4j.model.compute.Server;


public class TerminateNodesTest {

    @Rule public PluginTestRule j = new PluginTestRule();

    @Test
    public void destroy() {
        JCloudsCloud cloud = j.dummyCloud();

        Openstack os = cloud.getOpenstack();

        final Server keepServer = mock(Server.class);
        new RunningNode("openstack", "keep", keepServer);
        when(keepServer.getId()).thenReturn("keep");
        final Server terminateServer = mock(Server.class);
        RunningNode terminate = new RunningNode("openstack", "terminate", terminateServer);
        when(terminateServer.getId()).thenReturn("terminate");

        when(os.getServerById(any(String.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                String name = (String) invocation.getArguments()[0];
                if ("keep".equals(name)) return keepServer;
                if ("terminate".equals(name)) return terminateServer;
                throw new AssertionError();
            }
        });

        new TerminateNodes().apply(Collections.singletonList(terminate));

        verify(os).destroyServer(terminateServer);
        verify(os, never()).destroyServer(keepServer);
    }
}
