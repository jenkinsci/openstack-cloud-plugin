package jenkins.plugins.openstack.compute;

import hudson.model.TaskListener;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.LocalData;
import org.mockito.Mockito;
import org.openstack4j.api.exceptions.ConnectionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * @author ogondza.
 */
public class JCloudsSlaveTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test @LocalData
    public void loadConfigFromV20() throws Exception {
        JCloudsSlave s = (JCloudsSlave) j.jenkins.getNode("cloud-slave");
        try {
            s.getLauncher().launch(s.getComputer(), TaskListener.NULL);
            fail();
        } catch (ConnectionException ex) {
            // Unable to query the openstack for the ip
        }

        SlaveOptions so = s.getSlaveOptions();
        assertNotNull(so);
        assertEquals(42, (int) so.getRetentionTime());
        assertEquals("-verbose", so.getJvmOptions());
        assertEquals("8f3da277-c60e-444c-ab86-517e96ffe508", so.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, so.getSlaveType());
    }

    @Test @LocalData
    public void loadConfigFromV18() throws Exception {
        JCloudsSlave s = (JCloudsSlave) j.jenkins.getNode("cloud-slave");
        assertEquals("2235b04d-267c-4487-908f-e55d2e81c0a9", s.getServerId());
        try {
            s.getLauncher().launch(s.getComputer(), TaskListener.NULL);
            fail();
        } catch (ConnectionException ex) {
            // Unable to query the openstack for the ip
        }

        SlaveOptions so = s.getSlaveOptions();
        assertNotNull(so);
        assertEquals("2040d591-062a-4ccf-8f36-0a3340a1c51b", so.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, so.getSlaveType());
    }
}
