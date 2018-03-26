package jenkins.plugins.openstack.compute;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv2;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv3;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.LocalData;
import org.openstack4j.api.exceptions.ConnectionException;

import java.io.IOException;

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
public class PersistenceMigrationTest {

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
        LauncherFactory.SSH slaveType = (LauncherFactory.SSH) so.getLauncherFactory();
        assertEquals("8f3da277-c60e-444c-ab86-517e96ffe508", slaveType.getCredentialsId());
    }

    @Test @LocalData
    public void loadConfigFromV18() throws Exception {
        j.dummySshCredential("2040d591-062a-4ccf-8f36-0a3340a1c51b");
        // Node persisted
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
        LauncherFactory.SSH slaveType = (LauncherFactory.SSH) so.getLauncherFactory();
        assertEquals("2040d591-062a-4ccf-8f36-0a3340a1c51b", slaveType.getCredentialsId());

        // Cloud config persisted
        JCloudsCloud cloud = JCloudsCloud.getByName("OSCloud");
        JCloudsSlaveTemplate template = cloud.getTemplate("ath-integration-test");
        so = template.getRawSlaveOptions();
        assertEquals("jenkins-testing", so.getKeyPairName());
        assertThat(launcherOf(template), instanceOf(SSHLauncher.class));
    }

    @Test @LocalData
    public void loadConfigFromV24() throws Exception {
        JCloudsCloud jnlpCloud = JCloudsCloud.getByName("JNLP");
        JCloudsCloud sshCloud = JCloudsCloud.getByName("SSH");
        JCloudsCloud inheritedCloud = JCloudsCloud.getByName("INHERITED");

        assertThat(launcherOf(jnlpCloud), instanceOf(JNLPLauncher.class));
        assertThat(launcherOf(jnlpCloud.getTemplate("jnlp")), instanceOf(JNLPLauncher.class));
        assertThat(launcherOf(jnlpCloud.getTemplate("ssh")), instanceOf(SSHLauncher.class));

        assertThat(launcherOf(sshCloud), instanceOf(SSHLauncher.class));
        assertThat(launcherOf(sshCloud.getTemplate("jnlp")), instanceOf(JNLPLauncher.class));
        assertThat(launcherOf(sshCloud.getTemplate("ssh")), instanceOf(SSHLauncher.class));

        assertThat(launcherOf(inheritedCloud), instanceOf(SSHLauncher.class));
        assertThat(launcherOf(inheritedCloud.getTemplate("jnlp")), instanceOf(JNLPLauncher.class));
        assertThat(launcherOf(inheritedCloud.getTemplate("ssh")), instanceOf(SSHLauncher.class));
    }


    @Test @LocalData
    public void loadConfigFromV29() {
        JCloudsCloud v2Cloud = JCloudsCloud.getByName("v2");
        JCloudsCloud v3Cloud = JCloudsCloud.getByName("v3");

        OpenstackCredentialv2 v2 = (OpenstackCredentialv2) OpenstackCredentials.getCredential(v2Cloud.getCredentialId());
        assertEquals("tenant:user/******",CredentialsNameProvider.name(v2));
        assertEquals("OSQmsm29pf2vGWZEBlhAjUiJo/jhTfsUcMCgdIvwyXc=",v2.getPassword().getPlainText());

        OpenstackCredentialv3 v3 = (OpenstackCredentialv3) OpenstackCredentials.getCredential(v3Cloud.getCredentialId());
        assertEquals("domain:project:domain:user/******",CredentialsNameProvider.name(v3));
        assertEquals("OSQmsm29pf2vGWZEBlhAjUiJo/jhTfsUcMCgdIvwyXc=",v3.getPassword().getPlainText());
    }

    private static ComputerLauncher launcherOf(SlaveOptions.Holder holder) throws IOException {
        SlaveOptions options = holder.getEffectiveSlaveOptions();

        JCloudsSlave slave = mock(JCloudsSlave.class);
        when(slave.getNodeName()).thenReturn("fake");
        when(slave.getSlaveOptions()).thenReturn(options);
        when(slave.getPublicAddressIpv4()).thenReturn("42.42.42.42");

        LauncherFactory launcherFactory = options.getLauncherFactory();
        assertNotNull(launcherFactory);
        return launcherFactory.createLauncher(slave);
    }
}
