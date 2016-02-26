package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void scheduleSlaveDelete() throws Exception {
        int retentionTime = 1; // minute

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "template", "label", SlaveOptions.builder().retentionTime(retentionTime).build()
        );

        JCloudsCloud cloud = j.createCloudProvisioningSlaves(template);
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        assertEquals(1, computer.getRetentionTime());

        JCloudsRetentionStrategy strategy = (JCloudsRetentionStrategy) slave.getRetentionStrategy();
        strategy.check(computer);
        assertFalse("Slave should not be scheduled for deletion right away", computer.isPendingDelete());

        Thread.sleep(1000 * 61); // Wait for the slave to be idle long enough

        strategy.check(computer);
        assertTrue("Slave should be scheduled for deletion", computer.isPendingDelete());
    }
}
