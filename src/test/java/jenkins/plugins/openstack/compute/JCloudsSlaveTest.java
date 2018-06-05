package jenkins.plugins.openstack.compute;

import org.junit.Rule;
import org.junit.Test;

import jenkins.plugins.openstack.PluginTestRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JCloudsSlaveTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

	@Test
    public void shouldBeRetainedTrueWhenInstancesAreRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        assertTrue("Slave must be retained", slave.shouldBeRetained());
    }

	@Test
    public void shouldBeRetainedFalseWhenNoInstancesAreRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        assertFalse("Slave does not need to be retained", slave.shouldBeRetained());
    }

}
