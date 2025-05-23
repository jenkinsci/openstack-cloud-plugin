package jenkins.plugins.openstack.compute;

import static hudson.model.Label.get;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.User;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.InterruptedBuildAction;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TestBuilder;
import org.openstack4j.model.compute.Server;

/**
 * @author ogondza.
 */
public class JCloudsCleanupThreadTest {
    private static final Logger LOGGER = Logger.getLogger(JCloudsCleanupThreadTest.class.getName());

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void discardTemporarilyOfflineSlave() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2);
        JCloudsComputer computer = j.provision(cloud, "label").getComputer();

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        computer.setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/fake", 42));

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNull(
                AsyncResourceDisposer.get().getBacklog().toString(), j.jenkins.getComputer(computer.getDisplayName()));
    }

    @Test
    public void discardDisconnectedSlave() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2); // dont run during tests
        JCloudsComputer computer = j.provision(cloud, "label").getComputer();

        TimeUnit.SECONDS.sleep(4); // 4 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        computer.disconnect(new OfflineCause.ChannelTermination(new IOException("Broken badly")));
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        TimeUnit.SECONDS.sleep(4); // 4 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNull(
                AsyncResourceDisposer.get().getBacklog().toString(), j.jenkins.getComputer(computer.getDisplayName()));
    }

    @Test
    @Issue("JENKINS-50313")
    @Ignore("Not jet fixed")
    public void doNotDiscardDisconnectedSlaveTemporarilyOfflineBySomeone() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2);
        JCloudsComputer computer = j.provision(cloud, "label").getComputer();

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(User.current(), "For testing"));
        computer.disconnect(new OfflineCause.ChannelTermination(new IOException("Broken badly")));
        assertNotNull(j.jenkins.getComputer(computer.getDisplayName()));

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();
        assertNotNull(
                AsyncResourceDisposer.get().getBacklog().toString(), j.jenkins.getComputer(computer.getDisplayName()));
    }

    @Test
    public void doNotDeleteSlaveThatIsNotIdle() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2);
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();

        final BuildBlocker blocker = new BuildBlocker();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        p.getBuildersList().add(blocker);
        FreeStyleBuild build = p.scheduleBuild2(0).waitForStart();
        blocker.enter.block();
        assertTrue(build.isBuilding());
        assertEquals(build.getBuiltOn(), slave);

        //noinspection ThrowableNotThrown
        computer.doScheduleTermination();

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        assertTrue(build.isBuilding());
        assertEquals(slave, j.jenkins.getNode(slave.getDisplayName()));

        blocker.exit.signal();
        j.waitUntilNoActivity();

        assertFalse(build.isBuilding());
        j.assertBuildStatus(Result.SUCCESS, build);

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        assertNull(j.jenkins.getNode(slave.getDisplayName()));
    }

    @Test
    public void deleteMachinesNotConnectedToAnySlave() {
        try {
            JCloudsCloud cloud = j.dummyCloud();
            cloud.setCleanfreq(2);
            Server server = mock(Server.class);
            when(server.getId()).thenReturn("424242");
            when(server.getMetadata())
                    .thenReturn(Collections.singletonMap(
                            ServerScope.METADATA_KEY, new ServerScope.Build("deleted:42").toString()));
            Openstack os = cloud.getOpenstack();
            when(os.getServerById(eq("424242"))).thenReturn(server);
            when(os.getRunningNodes()).thenReturn(Collections.singletonList(server));

            TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
            j.triggerOpenstackSlaveCleanup();

            verify(os).destroyServer(server);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Unable to delete leaked fips");
        }
    }

    @Test
    public void deleteLeakedFip() {
        try {
            JCloudsCloud cloud = j.dummyCloud();
            Openstack os = cloud.getOpenstack();
            when(os.getFreeFipIds()).thenReturn(Arrays.asList("leaked1", "leaked2"));

            TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
            j.triggerOpenstackSlaveCleanup();

            verify(os).destroyFip("leaked1");
            verify(os).destroyFip("leaked2");
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Unable to delete leaked fips");
        }
    }

    @Test
    public void terminateNodeWithoutServer() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2);
        Openstack os = cloud.getOpenstack();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(get("label"));
        BuildBlocker blocker = new BuildBlocker();
        p.getBuildersList().add(blocker);

        FreeStyleBuild build = p.scheduleBuild2(0).getStartCondition().get();
        blocker.enter.block();
        assertTrue(build.isBuilding());

        when(os.getRunningNodes()).thenReturn(emptyList());
        String serverId = JCloudsComputer.getAll().get(0).getNode().getServerId();
        doThrow(new NoSuchElementException()).when(os).getServerById(eq(serverId));

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        j.waitUntilNoActivity();
        j.assertBuildStatus(Result.ABORTED, build);
        assertThat(
                build.getAction(InterruptedBuildAction.class).getCauses().get(0).getShortDescription(),
                startsWith("OpenStack server (" + serverId + ") is not running for computer "));
    }

    @Test
    public void terminateNodeWithShutoffServer() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions()
                        .getBuilder()
                        .retentionTime(0)
                        .instancesMin(1)
                        .instanceCap(1)
                        .build(),
                "label")));
        cloud.setCleanfreq(2);
        Openstack os = cloud.getOpenstack();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(get("label"));
        BuildBlocker blocker = new BuildBlocker();
        p.getBuildersList().add(blocker);

        FreeStyleBuild build = p.scheduleBuild2(0).getStartCondition().get();
        blocker.enter.block();
        assertTrue(build.isBuilding());

        List<Server> runningNodes = os.getRunningNodes();
        assertEquals(1, runningNodes.size());
        Server builtOn = runningNodes.get(0);

        // Server shut off
        when(os.getRunningNodes()).thenReturn(emptyList());
        when(builtOn.getStatus()).thenReturn(Server.Status.SHUTOFF);

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        j.waitUntilNoActivity();
        j.assertBuildStatus(Result.ABORTED, build);
        assertThat(
                build.getAction(InterruptedBuildAction.class).getCauses().get(0).getShortDescription(),
                startsWith("OpenStack server (" + builtOn.getId() + ") is not running for computer "));
    }

    @Test
    @Issue("jenkinsci/openstack-cloud-plugin#149")
    public void doNotTerminateNodeThatIsBeingProvisioned() throws Exception {
        // Simulate node stuck launching
        SlaveOptions options = j.defaultSlaveOptions()
                .getBuilder()
                .launcherFactory(LauncherFactory.JNLP.JNLP)
                .instanceCap(1)
                .build();
        JCloudsCloud cloud =
                j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(options, j.dummySlaveTemplate("label")));
        cloud.setCleanfreq(2);

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(get("label"));
        p.scheduleBuild2(0);

        Thread.sleep(2000); // For cloud to kick the machine while build is enqueued

        assertThat(j.jenkins.getNodes(), Matchers.iterableWithSize(1));

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        assertThat(j.jenkins.getNodes(), Matchers.iterableWithSize(1));
    }

    public static class BuildBlocker extends TestBuilder {
        private final OneShotEvent enter = new OneShotEvent();
        private final OneShotEvent exit = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            enter.signal();
            exit.block();
            return true;
        }

        public void awaitStarted() throws InterruptedException {
            enter.block();
        }

        public void signalDone() {
            exit.signal();
        }
    }
}
