package jenkins.plugins.openstack.compute;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.OfflineCause;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;

public class SingleUseGracePeriodTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void taskCompletionRemainsPendingAfterReconnect() throws Exception {
        verifySingleUseDeletion(false, true);
    }

    @Test
    public void taskCompletionRemainsPendingUntilGraceExpires() throws Exception {
        verifySingleUseDeletion(false, false);
    }

    @Test
    public void wrapperTeardownRemainsPendingAfterReconnect() throws Exception {
        verifySingleUseDeletion(true, true);
    }

    @Test
    public void wrapperTeardownRemainsPendingUntilGraceExpires() throws Exception {
        verifySingleUseDeletion(true, false);
    }

    private void verifySingleUseDeletion(boolean wrapper, boolean reconnect) throws Exception {
        SlaveOptions options = j.defaultSlaveOptions()
                .getBuilder()
                .fsRoot(j.jenkins.getRootDir().toPath().resolve("agent").toString())
                .retentionTime(wrapper ? -1 : 0)
                .disconnectGraceHours(1)
                .build();
        JCloudsCloud cloud =
                j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(options, j.dummySlaveTemplate("label")));
        JCloudsSlave node = j.provision(cloud, "label");
        JCloudsComputer computer = node.getComputer();
        FreeStyleProject project = j.createFreeStyleProject();
        project.setAssignedNode(node);
        if (wrapper) project.getBuildWrappersList().add(new JCloudsOneOffSlave());
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {
                try {
                    computer.disconnect(new OfflineCause.ChannelTermination(new IOException("Connection lost")))
                            .get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    throw new IOException(e);
                }
                return true;
            }
        });
        FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();
        // Losing the channel may fail the build; both completion paths must still preserve deletion.
        j.waitForCompletion(build);
        j.waitUntilNoActivity();
        assertTrue(node.hasDisconnectGracePeriod());
        assertTrue(computer.isPendingDelete());
        j.triggerOpenstackSlaveCleanup();
        assertNotNull(j.jenkins.getNode(node.getNodeName()));
        assertNotNull(cloud.getOpenstack().getServerById(node.getServerId()));

        if (reconnect) {
            j.stopJnlpSlave(computer.getName());
            j.connectJnlpSlave(computer.getName());
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((computer.getChannel() == null || node.hasDisconnectGracePeriod()) && System.nanoTime() < timeout) {
                Thread.sleep(100);
            }
            assertNotNull(computer.getChannel());
            assertFalse(node.hasDisconnectGracePeriod());
            assertTrue(computer.isPendingDelete());
            assertTrue(computer.isOffline());
            var nextBuild = project.scheduleBuild2(0);
            try {
                j.jenkins.getQueue().maintain();
                assertNotNull(j.jenkins.getQueue().getItem(project));
                assertFalse(nextBuild.getStartCondition().isDone());
            } finally {
                nextBuild.cancel(true);
            }
        } else {
            var deadline = JCloudsSlave.class.getDeclaredField("disconnectGraceDeadline");
            deadline.setAccessible(true);
            deadline.setLong(node, System.currentTimeMillis() - 1);
        }
        j.triggerOpenstackSlaveCleanup();
        assertNull(j.jenkins.getNode(node.getNodeName()));
    }

    @Test
    public void singleUseCompletionDoesNotReclassifyAdministratorDeletion() throws Exception {
        SlaveOptions options = j.defaultSlaveOptions()
                .getBuilder()
                .fsRoot(j.jenkins.getRootDir().toPath().resolve("agent").toString())
                .disconnectGraceHours(1)
                .build();
        JCloudsCloud cloud =
                j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(options, j.dummySlaveTemplate("label")));
        JCloudsSlave node = j.provision(cloud, "label");
        JCloudsComputer computer = node.getComputer();
        computer.doScheduleTermination();
        computer.scheduleSingleUseDeletion();
        computer.disconnect(new OfflineCause.ChannelTermination(new IOException("Connection lost")))
                .get(10, TimeUnit.SECONDS);
        assertFalse(node.hasDisconnectGracePeriod());
        j.triggerOpenstackSlaveCleanup();
        assertNull(j.jenkins.getNode(node.getNodeName()));
    }
}
