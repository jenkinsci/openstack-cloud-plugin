package jenkins.plugins.openstack.compute.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.compute.ComputeFloatingIPService;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.compute.domain.NovaFloatingIP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class OpenstackTest {

    public static final ClientResponseException CLIENT_RESPONSE_FIP_DISALLOWED = new ClientResponseException(
            "Policy doesn't allow compute_extension:floating_ip_pools to be performed",
            403
    );

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

    @Test
    public void deleteFloatingIpsWhenDeletingMachine() {
        Server server = mock(Server.class);
        when(server.getId()).thenReturn("instance-id");

        DeleteMachineSequencer sequencer = new DeleteMachineSequencer(server);

        // Instance exists at first then disappears after delete
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().servers().get(server.getId())).thenAnswer(sequencer.getServer());

        when(client.compute().servers().delete(server.getId())).thenAnswer(sequencer.deleteServer());

        ComputeFloatingIPService fips = client.compute().floatingIps();
        when(fips.list()).thenAnswer(sequencer.getAllFips());
        when(fips.deallocateIP(any(String.class))).thenAnswer(sequencer.deleteFip());

        Openstack os = new Openstack(client);
        os.destroyServer(server);

        verify(client.compute().servers()).delete(server.getId());
        verify(fips).deallocateIP("release-me");
        verify(fips, never()).deallocateIP("keep-me");
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/128")
    public void doNotFailPopulatingFipPools() throws Exception {
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().floatingIps()).thenThrow(CLIENT_RESPONSE_FIP_DISALLOWED);

        Openstack os = new Openstack(client);
        assertThat(os.getSortedIpPools(), Matchers.emptyIterable());
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/128")
    public void allocatingFipShouldFailIfFipsDisallowed() throws Exception {
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().floatingIps()).thenThrow(CLIENT_RESPONSE_FIP_DISALLOWED);

        Server server = mock(Server.class);
        when(server.getId()).thenReturn("instance-id");

        Openstack os = new Openstack(client);
        try {
            os.assignFloatingIp(server, "A1");
            fail();
        } catch (ClientResponseException ex) {
            assertThat(ex.getStatus(), equalTo(403));
        }
    }

    /**
     * Track the state of the openstack to be manifested by different client calls;
     */
    private class DeleteMachineSequencer {
        private volatile Server server;
        private final List<FloatingIP> fips = new ArrayList<>(Arrays.asList(
                NovaFloatingIP.builder().id("keep-me").instanceId("someone-elses").floatingIpAddress("0.0.0.0").build(),
                NovaFloatingIP.builder().id("release-me").instanceId("instance-id").floatingIpAddress("1.1.1.1").build()
        ));
        private ActionResponse success = ActionResponse.actionSuccess();
        private ActionResponse failure = ActionResponse.actionFailed("fake", 500);

        private DeleteMachineSequencer(Server server) {
            this.server = server;
        }

        private Answer<Server> getServer() {
            return new Answer<Server>() {
                @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                    return server;
                }
            };
        }

        private Answer<ActionResponse> deleteServer() {
            return new Answer<ActionResponse>() {
                @Override public ActionResponse answer(InvocationOnMock invocation) throws Throwable {
                    server = null;
                    return success;
                }
            };
        }

        private Answer<List<FloatingIP>> getAllFips() {
            return new Answer<List<FloatingIP>>() {
                @Override public List<FloatingIP> answer(InvocationOnMock invocation) throws Throwable {
                    return new ArrayList<>(fips); // Defensive copy so deleteFip can modify this
                }
            };
        }

        private Answer<ActionResponse> deleteFip() {
            return new Answer<ActionResponse>() {
                @Override public ActionResponse answer(InvocationOnMock invocation) throws Throwable {
                    String id = (String) invocation.getArguments()[0];
                    Iterator<FloatingIP> iter = fips.iterator();
                    while (iter.hasNext()) {
                        FloatingIP fip = iter.next();
                        if (fip.getId().equals(id)) {
                            iter.remove();
                            return success;
                        }
                    }
                    return failure;
                }
            };
        }
    }
}
