package jenkins.plugins.openstack.compute.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

public class OpenstackTest {

    @Test
    public void deleteAfterFailedBoot() {
        Openstack os = mock(Openstack.class, CALLS_REAL_METHODS);
        Server server = mock(Server.class);
        when(server.getStatus()).thenReturn(Server.Status.ERROR);
        when(server.getVmState()).thenReturn(null);

        Fault fault = mock(Fault.class);
        when(fault.getCode()).thenReturn(42);
        when(fault.getMessage()).thenReturn("It is broken!");
        when(fault.getDetails()).thenReturn("I told you once");
        when(server.getFault()).thenReturn(fault);

        doReturn(server).when(os)._bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        doThrow(new Openstack.ActionFailed("Fake deletion failure")).when(os).destroyServer(server);

        try {
            os.bootAndWaitActive(mock(ServerCreateBuilder.class), 1);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("status=ERROR vmState=null fault=42: It is broken! (I told you once)"));
            assertThat(ex.getSuppressed()[0].getMessage(), containsString("Fake deletion failure"));
        }

        verify(os, times(1)).destroyServer(server);
    }
}
