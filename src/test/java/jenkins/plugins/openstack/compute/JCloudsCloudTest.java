package jenkins.plugins.openstack.compute;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.util.Collection;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud.DescriptorImpl;
import jenkins.plugins.openstack.compute.internal.Openstack;

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
        WebAssert.assertLinkPresentWithText(page1, "Cloud (Openstack)");

        HtmlPage page2 = page.getAnchorByText("Cloud (Openstack)").click();
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

        JCloudsCloud original = j.dummyCloud();

        j.getInstance().clouds.add(original);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(original, j.getInstance().clouds.getByName("openstack-profile"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");

        j.assertEqualBeans(original, JCloudsCloud.getByName("openstack-profile"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");
    }

    @Test
    public void manullyProvisionAndKill() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        JCloudsCloud cloud = j.addCoud(j.dummyCloud(template));
        j.autoconnectJnlpSlaves();
        Openstack os = cloud.getOpenstack();
        Server provisioned = j.mockServer().name("provisioned").floatingIp("42.42.42.42").get();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenReturn(provisioned);
        when(os.updateInfo(any(Server.class))).thenReturn(provisioned);

        Collection<PlannedNode> slaves = cloud.provision(Label.get("label"), 1);
        assertEquals("Slave should be provisioned", 1, slaves.size());
        Computer computer = slaves.iterator().next().future.get().toComputer();
        assertTrue("Slave should be connected", computer.isOnline());

        computer.doDoDelete();
        assertTrue("Slave is temporarily offline", computer.isTemporarilyOffline());

        j.triggerOpenstackSlaveCleanup();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
    }

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        JCloudsCloud cloud = j.addCoud(j.dummyCloud(template));
        j.autoconnectJnlpSlaves();
        Openstack os = cloud.getOpenstack();
        Server provisioned = j.mockServer().name("provisioned").floatingIp("42.42.42.42").get();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenReturn(provisioned);
        when(os.updateInfo(any(Server.class))).thenReturn(provisioned);

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
}
