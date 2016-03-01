package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequestSettings;
import hudson.model.FreeStyleBuild;
import hudson.plugins.sshslaves.SSHLauncher;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.util.FormValidation;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud.DescriptorImpl;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.Future;

public class JCloudsCloudTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void incompleteteTestConnection() {
        DescriptorImpl desc = j.jenkins.getDescriptorByType(JCloudsCloud.DescriptorImpl.class);
        FormValidation v;

        v = desc.doTestConnection("REGION", null, "a:b", "passwd");
        assertEquals("Endpoint URL is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("REGION", "https://example.com", null, "passwd");
        assertEquals("Identity is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("REGION", "https://example.com", "a:b", null);
        assertEquals("Credential is required", FormValidation.Kind.ERROR, v.kind);
    }

    @Test
    public void failtoTestConnection() throws Exception {
        FormValidation validation = j.jenkins.getDescriptorByType(JCloudsCloud.DescriptorImpl.class)
                .doTestConnection(null, "https://example.com", "a", "a:b")
        ;

        assertEquals(FormValidation.Kind.ERROR, validation.kind);
        assertThat(validation.getMessage(), containsString("Cannot connect to specified cloud"));
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

    @Test
    public void testConfigRoundtrip() throws Exception {
        JCloudsCloud original = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl", 1, CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES, 600 * 1000, null, Collections.<JCloudsSlaveTemplate>emptyList(), true, "public"
        );
        j.jenkins.clouds.add(original);

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(original, j.getInstance().clouds.getByName("openstack"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");

        j.assertEqualBeans(original, JCloudsCloud.getByName("openstack"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");
    }

    @Test @SuppressWarnings("deprecation")
    public void manullyProvisionAndKill() throws Exception {
        Computer computer = j.provisionDummySlave("label").toComputer();
        assertTrue("Slave should be connected", computer.isOnline());

        computer.doDoDelete();
        assertTrue("Slave is temporarily offline", computer.isTemporarilyOffline());

        j.triggerOpenstackSlaveCleanup();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
    }

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        j.createCloudProvisioningDummySlaves("label");

        j.jenkins.setNumExecutors(0);
        FreeStyleProject p = j.createFreeStyleProject();
        // Provision with label
        p.setAssignedLabel(Label.get("label"));
        assertThat(j.buildAndAssertSuccess(p).getBuiltOn(), Matchers.instanceOf(JCloudsSlave.class));
        j.triggerOpenstackSlaveCleanup();

        // Provision without label
        p.setAssignedLabel(null);
        assertThat(j.buildAndAssertSuccess(p).getBuiltOn(), Matchers.instanceOf(JCloudsSlave.class));
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/31")
    public void abortProvisioningWhenOpenstackFails() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        JCloudsCloud cloud = j.dummyCloud(template);
        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenThrow(new Openstack.ActionFailed("It is broken, alright!"));

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        Future<FreeStyleBuild> started = p.scheduleBuild2(0).getStartCondition();

        Thread.sleep(1000);
        assertFalse(started.isDone());

        verify(os, atLeastOnce()).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/31")
    public void failToProvisionManuallyWhenOpenstackFails() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        final JCloudsCloud cloud = j.dummyCloud(template);
        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenThrow(new Openstack.ActionFailed("It is broken, alright!"));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.setPrintContentOnFailingStatusCode(false);
        Page page = wc.getPage(wc.addCrumb(new WebRequestSettings(
                new URL(wc.getContextPath() + "cloud/openstack/provision?name=template"),
                HttpMethod.POST
        )));

        assertThat(page.getWebResponse().getContentAsString(), containsString("It is broken, alright!"));

        verify(os, times(1)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/37")
    public void detectBootTimingOut() {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        final JCloudsCloud cloud = j.dummyCloud(template);
        Openstack os = cloud.getOpenstack();
        Server server = j.mockServer().name("provisioned").status(Server.Status.BUILD).get();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenCallRealMethod();
        when(os._bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenReturn(server);
        when(os.updateInfo(eq(server))).thenReturn(server);

        try {
            Server s = template.provision(cloud);
            System.out.println(s.getStatus());
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("Failed to boot server in time"));
            assertThat(ex.getMessage(), containsString("status=BUILD"));
        }

        verify(os).destroyServer(eq(server));
    }

    @Test @LocalData
    public void globalConfigMigrationFromV1() throws Exception {
        JCloudsCloud cloud = (JCloudsCloud) j.jenkins.getCloud("OSCloud");
        assertEquals("http://my.openstack:5000/v2.0", cloud.endPointUrl);
        assertEquals("tenant:user", cloud.identity);
        assertEquals(true, cloud.isFloatingIps());
        assertEquals("public", cloud.getFloatingIpPool());

        JCloudsSlaveTemplate template = cloud.getTemplate("ath-integration-test");
        assertEquals("16", template.hardwareId);
        assertEquals("ac98e93d-34a3-437d-a7ba-9ad24c02f5b2", template.imageId);
        assertEquals("my-network", template.networkId);
        assertEquals("1", template.numExecutors);
        assertEquals("/tmp/jenkins", template.getFsRoot());
        assertEquals("jenkins-testing", template.keyPairName);
        assertEquals(JCloudsCloud.SlaveType.SSH, template.slaveType);

        assertEquals(fileAsString("globalConfigMigrationFromV1/expected-userData"), template.getUserData());

        BasicSSHUserPrivateKey creds = (BasicSSHUserPrivateKey) SSHLauncher.lookupSystemCredentials(template.credentialsId);
        assertEquals("jenkins", creds.getUsername());
        assertEquals(fileAsString("globalConfigMigrationFromV1/expected-private-key"), creds.getPrivateKey());
    }

    private String fileAsString(String filename) throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream(getClass().getSimpleName() + "/" + filename));
    }
}
