package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.util.OneShotEvent;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;
import org.openstack4j.model.compute.Server;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * @author ogondza.
 */
public class JCloudsCleanupThreadTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void discardOutOfDiskSlave() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate("label")));
        JCloudsComputer computer = (JCloudsComputer) j.provision(cloud, "label").getComputer();

        j.triggerOpenstackSlaveCleanup();
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        computer.setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/fake", 42));

        j.triggerOpenstackSlaveCleanup();
        assertNull(AsyncResourceDisposer.get().getBacklog().toString(), j.jenkins.getComputer(computer.getDisplayName()));
    }

    @Test
    public void doNotDeleteSlaveThatIsNotIdle() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate("label")));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.getComputer();

        final OneShotEvent block = new OneShotEvent();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                block.block();
                return true;
            }
        });
        FreeStyleBuild build = p.scheduleBuild2(0).waitForStart();
        assertTrue(build.isBuilding());
        Thread.sleep(500); // Wait for correct computer to be assigned - master until that
        assertEquals(build.getBuiltOn(), slave);

        computer.doScheduleTermination();
        j.triggerOpenstackSlaveCleanup();

        assertTrue(build.isBuilding());
        assertEquals(slave, j.jenkins.getNode(slave.getDisplayName()));

        block.signal();
        j.waitUntilNoActivity();

        assertFalse(build.isBuilding());
        j.assertBuildStatus(Result.SUCCESS, build);

        j.triggerOpenstackSlaveCleanup();

        assertNull(j.jenkins.getNode(slave.getDisplayName()));
    }

    @Test
    public void deleteMachinesNotConnectedToAnySlave() {
        JCloudsCloud cloud = j.dummyCloud();
        Server server = mock(Server.class);
        when(server.getId()).thenReturn("424242");
        when(server.getMetadata()).thenReturn(Collections.singletonMap(
                ServerScope.METADATA_KEY, new ServerScope.Build("deleted:42").toString()
        ));
        Openstack os = cloud.getOpenstack();
        when(os.getServerById(eq("424242"))).thenReturn(server);
        when(os.getRunningNodes()).thenReturn(Collections.singletonList(server));

        j.triggerOpenstackSlaveCleanup();

        verify(os).destroyServer(server);
    }

    @Test
    public void deleteLeakedFip() throws Exception {
        JCloudsCloud cloud = j.dummyCloud();
        Openstack os = cloud.getOpenstack();
        when(os.getFreeFipIds()).thenReturn(Arrays.asList("busy1", "leaked")).thenReturn(Arrays.asList("leaked", "busy2"));

        j.triggerOpenstackSlaveCleanup();
        j.triggerOpenstackSlaveCleanup();

        verify(os).destroyFip("leaked");
        verify(os, never()).destroyFip("busy1");
        verify(os, never()).destroyFip("busy2");
    }
}
