package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import hudson.plugins.sshslaves.SSHLauncher;
import jenkins.plugins.openstack.GlobalConfig;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.apache.commons.io.IOUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import hudson.model.Label;
import hudson.util.FormValidation;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud.DescriptorImpl;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

public class JCloudsCloudTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void failToTestConnection() {
        DescriptorImpl desc = j.getCloudDescriptor();
        FormValidation v;
        FormValidation validation = j.jenkins.getDescriptorByType(JCloudsCloud.DescriptorImpl.class)
                .doTestConnection("https://example.com", "user", "password", "project", "domain", "REGION", "zone");

        v = desc.doTestConnection(null, "user", "passwd", "project", "domain", "REGION", "zone");
        assertEquals("Endpoint URL is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("https://example.com", null, "passwd", "project", "domain", "REGION", "zone");
        assertEquals("Identity is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("https://example.com", "user", null, "project", "domain", "REGION", "zone");
        assertEquals("Credential is required", FormValidation.Kind.ERROR, v.kind);

        Openstack os = j.fakeOpenstackFactory();
        when(os.sanityCheck()).thenReturn(new NullPointerException("It is broken, alright?"));

        v = desc.doTestConnection("https://example.com", "a", "a:b", null, null, "REGION", "zone");
        assertThat(v.getMessage(), containsString("It is broken, alright?"));
        assertEquals(FormValidation.Kind.WARNING, v.kind);

        v = desc.doTestConnection("https://example.com", "user", "password", null, "domain", "REGION", "zone");
        assertEquals("Project is required", FormValidation.Kind.WARNING, v.kind);

        v = desc.doTestConnection("https://example.com", "user", "password", "project", null, "REGION", "zone");
        assertEquals("Domain is required", FormValidation.Kind.WARNING, v.kind);
    }

    @Test
    public void testConfigurationUI() throws Exception {
        j.recipeLoadCurrentPlugin();
        j.configRoundtrip();
        HtmlPage page = j.createWebClient().goTo("configure");
        final String pageText = page.asText();
        assertTrue("Cloud Section must be present in the global configuration ", pageText.contains("Cloud"));

        final HtmlForm configForm = page.getFormByName("config");
        final HtmlButton buttonByCaption = configForm.getButtonByCaption("Add a new cloud");
        HtmlPage page1 = buttonByCaption.click();
        WebAssert.assertLinkPresentWithText(page1, "Cloud (OpenStack)");

        HtmlPage page2 = page.getAnchorByText("Cloud (OpenStack)").click();
        WebAssert.assertInputPresent(page2, "_.endPointUrl");
        WebAssert.assertInputPresent(page2, "_.identity");
        WebAssert.assertInputPresent(page2, "_.credential");
        WebAssert.assertInputPresent(page2, "_.instanceCap");
        WebAssert.assertInputPresent(page2, "_.retentionTime");

        HtmlForm configForm2 = page2.getFormByName("config");
        HtmlButton testConnectionButton = configForm2.getButtonByCaption("Test Connection");
        HtmlButton deleteCloudButton = configForm2.getButtonByCaption("Delete cloud");
        assertNotNull(testConnectionButton);
        assertNotNull(deleteCloudButton);
    }

    @Test @Ignore("HtmlUnit is not able to trigger form validation")
    public void presentUIDefaults() throws Exception {
        SlaveOptions DEF = ((JCloudsCloud.DescriptorImpl) j.jenkins.getDescriptorOrDie(JCloudsCloud.class)).getDefaultOptions();


        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate("template", "label", new SlaveOptions(
                "img", "hw", "nw", "ud", 1, "public", "sg", "az", 2, "kp", 3, "jvmo", "fsRoot", "cid", JCloudsCloud.SlaveType.JNLP, 4
        ));
        JCloudsCloud cloud = new JCloudsCloud("openstack", "identity", "credential", "endPointUrl", "project", "domain", 5, 20, 20, 20, new SlaveOptions(
                "IMG", "HW", "NW", "UD", 6, null, "SG", "AZ", 7, "KP", 8, "JVMO", "FSrOOT", "CID", JCloudsCloud.SlaveType.SSH, 9
        ), Arrays.asList(template), true, "region", "zone");
        j.jenkins.clouds.add(cloud);

        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("configure");
        GlobalConfig.Cloud c = GlobalConfig.addCloud(page);
        c.openAdvanced();

        // TODO image, network, hardware, userdata, credentialsId, slavetype, floatingips

//        assertEquals("IMG", c.value("imageId"));
//        assertEquals(DEF.getImageId(), c.def("imageId"));

        assertEquals("6", c.value("instanceCap"));
        assertEquals(String.valueOf(DEF.getInstanceCap()), c.def("instanceCap"));

        assertEquals("SG", c.value("securityGroups"));
        assertEquals(DEF.getSecurityGroups(), c.def("securityGroups"));

        assertEquals("AZ", c.value("availabilityZone"));
        assertEquals(DEF.getAvailabilityZone(), c.def("availabilityZone"));

        assertEquals("7", c.value("startTimeout"));
        assertEquals(String.valueOf(DEF.getStartTimeout()), c.def("startTimeout"));

        assertEquals("KP", c.value("keyPairName"));
        assertEquals(DEF.getKeyPairName(), c.def("keyPairName"));

        assertEquals("8", c.value("numExecutors"));
        assertEquals(String.valueOf(DEF.getNumExecutors()), c.def("numExecutors"));

        assertEquals("JVMO", c.value("jvmOptions"));
        assertEquals(DEF.getJvmOptions(), c.def("jvmOptions"));

        assertEquals("FSrOOT", c.value("fsRoot"));
        assertEquals(DEF.getFsRoot(), c.def("fsRoot"));

        assertEquals("9", c.value("retentionTime"));
        assertEquals(String.valueOf(DEF.getRetentionTime()), c.def("retentionTime"));
    }

    @Test
    public void eraseDefaults() {
        int biggerInstanceCap = j.getCloudDescriptor().getDefaultOptions().getInstanceCap() * 2;

        // Base tests on cloud defaults as that is the baseline for erasure
        SlaveOptions opts = j.getCloudDescriptor().getDefaultOptions().getBuilder().instanceCap(biggerInstanceCap).slaveType(JCloudsCloud.SlaveType.SSH).build();
        JCloudsCloud cloud = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl", "project", "domain", 5, 5, 5, 5, opts, Collections.<JCloudsSlaveTemplate>emptyList(), true, "region",
                "zone");

        assertEquals(opts, cloud.getEffectiveSlaveOptions());
        assertEquals(SlaveOptions.builder().instanceCap(biggerInstanceCap).build(), cloud.getRawSlaveOptions());
    }

    @Test
    public void testConfigRoundtrip() throws Exception {
        String beans = "name,identity,credential,endPointUrl,project,domain,region,zone";
        JCloudsCloud original = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl",
                "project", "domain", 1, 10, 600 * 1000, 600 * 1000, j.dummySlaveOptions(),
                Collections.<JCloudsSlaveTemplate>emptyList(), true, "region", "zone");
        j.jenkins.clouds.add(original);

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        JCloudsCloud actual = JCloudsCloud.getByName("openstack");
        assertSame(j.getInstance().clouds.getByName("openstack"), actual);
        j.assertEqualBeans(original, actual, beans);
        assertEquals(original.getRawSlaveOptions(), JCloudsCloud.getByName("openstack").getRawSlaveOptions());
    }

    @Test @LocalData
    public void globalConfigMigrationFromV1() throws Exception {
        JCloudsCloud cloud = (JCloudsCloud) j.jenkins.getCloud("OSCloud");
        assertEquals("http://my.openstack:5000/v2.0", cloud.endPointUrl);
        assertEquals("tenant:user", cloud.identity);
        SlaveOptions co = cloud.getEffectiveSlaveOptions();
        assertEquals("public", co.getFloatingIpPool());
        assertEquals(31, (int) co.getRetentionTime());
        assertEquals(9, (int) co.getInstanceCap());
        assertEquals(600001, (int) co.getStartTimeout());

        JCloudsSlaveTemplate template = cloud.getTemplate("ath-integration-test");
        assertEquals(Label.parse("label"), template.getLabelSet());
        SlaveOptions to = template.getEffectiveSlaveOptions();
        assertEquals("16", to.getHardwareId());
        assertEquals("ac98e93d-34a3-437d-a7ba-9ad24c02f5b2", to.getImageId());
        assertEquals("my-network", to.getNetworkId());
        assertEquals(1, (int) to.getNumExecutors());
        assertEquals(0, (int) to.getRetentionTime()); // overrideRetentionTime though deprecated, should be honored
        assertEquals("/tmp/jenkins", to.getFsRoot());
        assertEquals("jenkins-testing", to.getKeyPairName());
        assertEquals(JCloudsCloud.SlaveType.SSH, to.getSlaveType());
        assertEquals("default", to.getSecurityGroups());
        assertEquals("zone", to.getAvailabilityZone());

        assertEquals(fileAsString("globalConfigMigrationFromV1/expected-userData"), template.getUserData());

        BasicSSHUserPrivateKey creds = (BasicSSHUserPrivateKey) SSHLauncher.lookupSystemCredentials(to.getCredentialsId());
        assertEquals("jenkins", creds.getUsername());
        assertEquals(fileAsString("globalConfigMigrationFromV1/expected-private-key"), creds.getPrivateKey());

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));
    }

    private String fileAsString(String filename) throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream(getClass().getSimpleName() + "/" + filename));
    }
}
