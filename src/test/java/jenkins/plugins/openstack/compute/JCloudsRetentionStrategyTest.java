package jenkins.plugins.openstack.compute;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;
import static org.junit.Assert.*;

import hudson.model.Computer;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void scheduleSlaveDelete() throws Exception {
        int retentionTime = 1; // minute

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "template", "imageId", "hardwareId", "label", null, "42",
                "-verbose", "/tmp/slave", false, retentionTime, "keyPairName", "networkId",
                "securityGroups", "", JCloudsCloud.SlaveType.JNLP, "availabilityZone"
        );

        JCloudsCloud cloud = j.createCloudProvisioningSlaves(template);
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        assertEquals(1, computer.getRetentionTime());

        slave.getRetentionStrategy().check(slave.toComputer());
        assertFalse("Slave should not be scheduled for deletion right away", computer.isPendingDelete());

        Thread.sleep(1000 * 61); // Wait for the slave to be idle long enough

        slave.getRetentionStrategy().check(slave.toComputer());
        assertTrue("Slave should be scheduled for deletion", computer.isPendingDelete());
    }
}
