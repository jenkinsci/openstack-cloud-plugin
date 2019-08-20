/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.openstack;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import hudson.Functions;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.SlaveOptions;
import jenkins.plugins.openstack.compute.UserDataConfig;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv2;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv3;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.junit.Rule;
import org.junit.Test;

import java.net.URISyntaxException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// The tests depends on JCasC ability to enforce javax.annotations.* - which is convenient but somewhat surprising for
// plugin authors. This adds test coverage needed to verify things still works.
public class JcascTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("JcascTest/jcasc.yaml")
    public void configure() {
        JCloudsCloud c = JCloudsCloud.getByName("foo");
        assertEquals("foo", c.name);
        assertEquals("https://acme.com:5000", c.getEndPointUrl());
        assertTrue(c.getIgnoreSsl());
        assertEquals("foo", c.getZone());
        { // Credentials
            assertEquals("openstack_service_credentials", c.getCredentialsId());
            OpenstackCredentialv3 cv3 = (OpenstackCredentialv3) OpenstackCredentials.getCredential(c.getCredentialsId());
            assertEquals("foo", cv3.getUsername());
            assertEquals("bar", cv3.getPassword().getPlainText());
            assertEquals("acme.com", cv3.getUserDomain());
            assertEquals("casc", cv3.getProjectName());
            assertEquals("acme.com", cv3.getProjectDomain());

            OpenstackCredentialv2 cv2 = (OpenstackCredentialv2) OpenstackCredentials.getCredential("openstack_service_credentialsV2");
            assertEquals("username", cv2.getUsername());
            assertEquals("pwd", cv2.getPassword().getPlainText());
            assertEquals("tnt", cv2.getTenant());
        }

        { // Slave options
            SlaveOptions co = c.getRawSlaveOptions();
            assertEquals("Image Name", ((BootSource.Image) co.getBootSource()).getName());

            assertEquals("hid", co.getHardwareId());
            assertEquals("net", co.getNetworkId());
            assertEquals("user-data-id", co.getUserDataId());
            assertThat(UserDataConfig.resolve(co.getUserDataId()).trim(), allOf(startsWith("#cloud-config"), endsWith("system: false")));

            assertEquals((Integer) 11, co.getInstanceCap());
            assertEquals((Integer) 1, co.getInstancesMin());
            assertEquals("baz", co.getFloatingIpPool());
            assertEquals("s1,s2", co.getSecurityGroups());
            assertEquals("bax", co.getAvailabilityZone());
            assertEquals((Integer) 15, co.getStartTimeout());
            assertEquals("key", co.getKeyPairName());
            assertEquals((Integer) 2, co.getNumExecutors());
            assertEquals("-Xmx1G", co.getJvmOptions());
            assertEquals("/tmp/foo", co.getFsRoot());
            assertEquals(((Integer) 42), co.getRetentionTime());
            LauncherFactory.SSH lf = (LauncherFactory.SSH) co.getLauncherFactory();
            assertEquals(PluginTestRule.mkListOfNodeProperties(1,3), co.getNodeProperties());
            assertEquals("/bin/true", lf.getJavaPath());
            assertEquals("openstack_ssh_key", lf.getCredentialsId());
            BasicSSHUserPrivateKey sshcreds = PluginTestRule.extractSshCredentials(lf);
            assertEquals("jenkins", sshcreds.getUsername());
            assertThat(sshcreds.getPrivateKey(), containsString("kh2nsVg0sOMNkhkAAAAMb2dvbmR6YUBhcmNoAQIDBAUGBw=="));
        }

        // The only use-case for empty template is when all options are inherited by a single configured template
        JCloudsSlaveTemplate t = c.getTemplate("empty");
        assertEquals("linux", t.getLabels());
        assertEquals(SlaveOptions.empty(), t.getRawSlaveOptions());

        SlaveOptions jso = c.getTemplate("jnlp").getRawSlaveOptions();
        assertThat(jso.getLauncherFactory(), instanceOf(LauncherFactory.JNLP.class));

        BootSource.VolumeSnapshot vs = (BootSource.VolumeSnapshot) c.getTemplate("volumeSnapshot").getRawSlaveOptions().getBootSource();
        assertEquals("Volume name", vs.getName());

        BootSource.VolumeFromImage vfi = (BootSource.VolumeFromImage) c.getTemplate("volumeFromImage").getRawSlaveOptions().getBootSource();
        assertEquals("Volume name", vfi.getName());
        assertEquals(15, vfi.getVolumeSize());

        JCloudsSlaveTemplate templateWithCustomNodeProperties = c.getTemplate("customNodeProperties");
        assertEquals(PluginTestRule.mkListOfNodeProperties(2), templateWithCustomNodeProperties.getRawSlaveOptions().getNodeProperties());
    }

    @Test
    public void incompleteCloudConfig() throws Exception {
        assertFailsWith("missing-cloud-name", "name is required");
        assertFailsWith("missing-cloud-endpointUrl", "endPointUrl is required");
        assertFailsWith("missing-cloud-credentialsId", "credentialsId is required");

        assertFailsWith("missing-template-name", "name is required");
        assertFailsWith("missing-template-label", "labels is required");

        assertFailsWith("missing-ssh-launcher-credential", "credentialsId is required");
    }

    // TODO test incomplete config fails in expected way
    // - references not resolvable: userData, credentials
    // Not trivial as at the time plugin is configured the referenced entities might not yet be configured. So it needs
    // to accept what it cannot reference but should still fail somehow
    // @Test
    //public void resolveUserData() throws Exception {
    //}

    private void assertFailsWith(final String filename, String expected) {
        try {
            applyConfig(filename);
            fail("Applying " + filename + " did not fail (in an expected way)");
        } catch (ConfiguratorException ex) {
            assertThat(Functions.printThrowable(ex.getCause()), containsString(expected));
        }
    }

    private void applyConfig(String filename) throws ConfiguratorException {
        try {
            ConfigurationAsCode.get().configure(getClass().getResource("JcascTest/" + filename + ".yaml").toURI().toString());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }
}
