package jenkins.plugins.openstack.compute;

import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.LocalData;
import org.openstack4j.api.exceptions.ConnectionException;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test @LocalData
    public void loadConfigFromV24() throws Exception {
        JCloudsCloud jnlpCloud = JCloudsCloud.getByName("JNLP");
        JCloudsCloud sshCloud = JCloudsCloud.getByName("SSH");

        assertThat(producesLauncher(jnlpCloud), instanceOf(JNLPLauncher.class));
        assertThat(producesLauncher(jnlpCloud.getTemplate("jnlp")), instanceOf(JNLPLauncher.class));
        assertThat(producesLauncher(jnlpCloud.getTemplate("ssh")), instanceOf(SSHLauncher.class));

        assertThat(producesLauncher(sshCloud), instanceOf(SSHLauncher.class));
        assertThat(producesLauncher(sshCloud.getTemplate("jnlp")), instanceOf(JNLPLauncher.class));
        assertThat(producesLauncher(sshCloud.getTemplate("ssh")), instanceOf(SSHLauncher.class));
    }

    private static ComputerLauncher producesLauncher(SlaveOptions.Holder holder) throws IOException {
        SlaveOptions options = holder.getEffectiveSlaveOptions();

        JCloudsSlave slave = mock(JCloudsSlave.class);
        when(slave.getNodeName()).thenReturn("fake");
        when(slave.getSlaveOptions()).thenReturn(options);
        when(slave.getPublicAddressIpv4()).thenReturn("42.42.42.42");

        return options.getSlaveType().createLauncher(slave);
    }
}
