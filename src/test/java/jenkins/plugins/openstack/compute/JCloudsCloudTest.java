package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
        JCloudsCloud original = j.dummyCloud();

        j.getInstance().clouds.add(original);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(original, j.getInstance().clouds.getByName("openstack"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");

        j.assertEqualBeans(original, JCloudsCloud.getByName("openstack"),
                "identity,credential,endPointUrl,instanceCap,retentionTime,floatingIps");
    }

    @Test
    public void manullyProvisionAndKill() throws Exception {
        Computer computer = j.provisionDummySlave().toComputer();
        assertTrue("Slave should be connected", computer.isOnline());

        computer.doDoDelete();
        assertTrue("Slave is temporarily offline", computer.isTemporarilyOffline());

        j.triggerOpenstackSlaveCleanup();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
    }

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        j.configureDummySlaveToBeProvisioned();

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
