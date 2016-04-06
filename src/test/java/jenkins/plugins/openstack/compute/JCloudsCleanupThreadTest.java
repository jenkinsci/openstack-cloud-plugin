package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.util.OneShotEvent;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestBuilder;

import java.io.IOException;

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
        assertNull(j.jenkins.getComputer(computer.getDisplayName()));
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
        //j.interactiveBreak();
        assertEquals(build.getBuiltOn(), slave);

        computer.doDoDelete(); // simulate deletion from UI
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
}
