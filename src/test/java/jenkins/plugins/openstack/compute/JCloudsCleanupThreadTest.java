package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.startsWith;
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
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.util.OneShotEvent;
import jenkins.model.InterruptedBuildAction;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
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

        final BuildBlocker blocker = new BuildBlocker();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        p.getBuildersList().add(blocker);
        FreeStyleBuild build = p.scheduleBuild2(0).waitForStart();
        blocker.enter.block();
        assertTrue(build.isBuilding());
        assertEquals(build.getBuiltOn(), slave);

        computer.doScheduleTermination();
        j.triggerOpenstackSlaveCleanup();

        assertTrue(build.isBuilding());
        assertEquals(slave, j.jenkins.getNode(slave.getDisplayName()));

        blocker.exit.signal();
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

    @Test
    public void terminateNodeWithoutServer() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate("label")));
        Openstack os = cloud.getOpenstack();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        BuildBlocker blocker = new BuildBlocker();
        p.getBuildersList().add(blocker);

        FreeStyleBuild build = p.scheduleBuild2(0).getStartCondition().get();
        blocker.enter.block();
        assertTrue(build.isBuilding());

        when(os.getRunningNodes()).thenReturn(Collections.<Server>emptyList());
        j.triggerOpenstackSlaveCleanup();

        j.assertBuildStatus(Result.ABORTED, build);
        assertThat(
                build.getAction(InterruptedBuildAction.class).getCauses().get(0).getShortDescription(),
                startsWith("No OpenStack server running for computer")
        );
    }

    @Test @Issue("jenkinsci/openstack-cloud-plugin#149")
    public void doNotTerminateNodeThatIsBeingProvisioned() throws Exception {
        // Simulate node stuck launching
        SlaveOptions options = j.defaultSlaveOptions().getBuilder().launcherFactory(LauncherFactory.JNLP.JNLP).instanceCap(1).build();
        j.configureSlaveProvisioning(j.dummyCloud(options, j.dummySlaveTemplate("label")));

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        p.scheduleBuild2(0);

        Thread.sleep(2000); // For cloud to kick the machine while build is enqueued

        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(1));

        j.triggerOpenstackSlaveCleanup();

        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(1));
    }

    private static class BuildBlocker extends TestBuilder {
        private final OneShotEvent enter = new OneShotEvent();
        private final OneShotEvent exit = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            enter.signal();
            exit.block();
            return true;
        }
    }
}
