package jenkins.plugins.openstack.compute;

import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.LocalData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author ogondza.
 */
public class JCloudsSlaveTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test @LocalData
    public void loadConfigFromV20() throws Exception {
        JCloudsSlave c = (JCloudsSlave) j.jenkins.getComputer("cloud-slave").getNode();
        SlaveOptions so = c.getSlaveOptions();

        assertNotNull(so);
        assertEquals(42, (int) so.getRetentionTime());
        assertEquals("-verbose", so.getJvmOptions());
        assertEquals("8f3da277-c60e-444c-ab86-517e96ffe508", so.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, so.getSlaveType());
    }
}
