package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import hudson.model.Computer;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;

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
}
