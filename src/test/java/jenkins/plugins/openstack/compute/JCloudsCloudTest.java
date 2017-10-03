package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.gargoylesoftware.htmlunit.ConfirmHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import hudson.model.Item;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.SidACL;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.GlobalConfig;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.acls.sid.Sid;
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
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.kohsuke.stapler.Stapler;
import org.openstack4j.api.OSClient;
import org.openstack4j.openstack.compute.domain.NovaServer;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

public class JCloudsCloudTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test @Issue("JENKINS-39282") // The problem does not manifest in jenkins-test-harness - created as a regression test
    public void guavaLeak() throws Exception {
        NovaServer server = mock(NovaServer.class, CALLS_REAL_METHODS);
        server.id = "424242";
        assertThat(server.toString(), containsString("424242"));
    }

    @Test
    public void failToTestConnection() {
        DescriptorImpl desc = j.getCloudDescriptor();
        FormValidation v;

        v = desc.doTestConnection("REGION", null, "a:b", "passwd");
        assertEquals("Endpoint URL is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("REGION", "https://example.com", null, "passwd");
        assertEquals("Identity is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection("REGION", "https://example.com", "a:b", null);
        assertEquals("Credential is required", FormValidation.Kind.ERROR, v.kind);

        v = desc.doTestConnection(null, "https://example.com", "a", "a:b");
        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertThat(v.getMessage(), containsString("Cannot connect to specified cloud"));

        Openstack os = j.fakeOpenstackFactory();
        when(os.sanityCheck()).thenReturn(new NullPointerException("It is broken, alright?"));

        v = desc.doTestConnection(null, "https://example.com", "a", "a:b");
        assertEquals(FormValidation.Kind.WARNING, v.kind);
        assertThat(v.getMessage(), containsString("It is broken, alright?"));
    }

    @Test
    public void testConfigurationUI() throws Exception {
        j.recipeLoadCurrentPlugin();
        j.configRoundtrip();
        HtmlPage page = j.createWebClient().goTo("configure");
        final String pageText = page.asText();
        assertTrue("Cloud Section must be present in the global configuration ", pageText.contains("Cloud"));

        final HtmlForm configForm = page.getFormByName("config");
        final HtmlButton buttonByCaption = HtmlFormUtil.getButtonByCaption(configForm, "Add a new cloud");
        HtmlPage page1 = buttonByCaption.click();
        WebAssert.assertLinkPresentWithText(page1, "Cloud (OpenStack)");

        HtmlPage page2 = page.getAnchorByText("Cloud (OpenStack)").click();
        HtmlForm configForm2 = page2.getFormByName("config");
        for (int i = 0; i < 10; i++) { // Wait for JS
            try {
                HtmlFormUtil.getButtonByCaption(configForm2, "Test Connection");
                break;
            } catch (ElementNotFoundException ex) {
                Thread.sleep(1000);
            }
        }

        WebAssert.assertInputPresent(page2, "_.endPointUrl");
        WebAssert.assertInputPresent(page2, "_.identity");
        WebAssert.assertInputPresent(page2, "_.credential");
        WebAssert.assertInputPresent(page2, "_.instanceCap");
        WebAssert.assertInputPresent(page2, "_.retentionTime");

        HtmlFormUtil.getButtonByCaption(configForm2, "Test Connection");
        HtmlFormUtil.getButtonByCaption(configForm2, "Delete cloud");
    }

    @Test @Ignore("HtmlUnit is not able to trigger form validation")
    public void presentUIDefaults() throws Exception {
        SlaveOptions DEF = DescriptorImpl.getDefaultOptions();

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate("template", "label", new SlaveOptions(
                new BootSource.Image("iid"), "hw", "nw", "ud", 1, "public", "sg", "az", 2, "kp", 3, "jvmo", "fsRoot", LauncherFactory.JNLP.JNLP, 4
        ));
        JCloudsCloud cloud = new JCloudsCloud("openstack", "identity", "credential", "endPointUrl", "zone", new SlaveOptions(
                new BootSource.VolumeSnapshot("vsid"), "HW", "NW", "UD", 6, null, "SG", "AZ", 7, "KP", 8, "JVMO", "FSrOOT", new LauncherFactory.SSH("cid"), 9
        ), Collections.singletonList(template));
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
        int biggerInstanceCap = DescriptorImpl.getDefaultOptions().getInstanceCap() * 2;

        // Base tests on cloud defaults as that is the baseline for erasure
        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredential("cid"));
        SlaveOptions opts = DescriptorImpl.getDefaultOptions().getBuilder().instanceCap(biggerInstanceCap).launcherFactory(slaveType).build();
        JCloudsCloud cloud = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl", "zone", opts, Collections.<JCloudsSlaveTemplate>emptyList()
        );

        assertEquals(opts, cloud.getEffectiveSlaveOptions());
        SlaveOptions expected = SlaveOptions.builder().instanceCap(biggerInstanceCap).launcherFactory(slaveType).build();
        assertEquals(expected, cloud.getRawSlaveOptions());
    }

    @Test
    public void testConfigRoundtrip() throws Exception {
        String beans = "identity,credential,endPointUrl,zone";
        JCloudsCloud original = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl", "zone",
                j.defaultSlaveOptions(),
                Collections.<JCloudsSlaveTemplate>emptyList()
        );
        j.jenkins.clouds.add(original);

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        JCloudsCloud actual = JCloudsCloud.getByName("openstack");
        assertSame(j.getInstance().clouds.getByName("openstack"), actual);
        j.assertEqualBeans(original, actual, beans);
        assertEquals(original.getRawSlaveOptions(), JCloudsCloud.getByName("openstack").getRawSlaveOptions());
    }

    @Test
    public void configRoundtripNullZone() throws Exception {
        JCloudsCloud original = new JCloudsCloud(
                "openstack", "identity", "credential", "endPointUrl", null,
                j.defaultSlaveOptions(),
                Collections.<JCloudsSlaveTemplate>emptyList()
        );
        j.jenkins.clouds.add(original);

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));
        assertNull(JCloudsCloud.getByName("openstack").zone);
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
        assertEquals("ac98e93d-34a3-437d-a7ba-9ad24c02f5b2", ((BootSource.Image) to.getBootSource()).getName());
        assertEquals("my-network", to.getNetworkId());
        assertEquals(1, (int) to.getNumExecutors());
        assertEquals(0, (int) to.getRetentionTime()); // overrideRetentionTime though deprecated, should be honored
        assertEquals("/tmp/jenkins", to.getFsRoot());
        assertEquals("jenkins-testing", to.getKeyPairName());
        assertThat(to.getLauncherFactory(), instanceOf(LauncherFactory.SSH.class));
        assertEquals("default", to.getSecurityGroups());
        assertEquals("zone", to.getAvailabilityZone());
        assertEquals("jenkins.plugins.openstack.compute.UserDataConfig.1455188317989", to.getUserDataId());

        final String expectedUserData = toUnixEols(fileAsString("globalConfigMigrationFromV1/expected-userData"));
        final String actualUserData = toUnixEols(template.getUserData());
        assertEquals(expectedUserData, actualUserData);

        BasicSSHUserPrivateKey creds = (BasicSSHUserPrivateKey) SSHLauncher.lookupSystemCredentials(((LauncherFactory.SSH) to.getLauncherFactory()).getCredentialsId());
        assertEquals("jenkins", creds.getUsername());
        final String expectedPrivateKey = toUnixEols(fileAsString("globalConfigMigrationFromV1/expected-private-key"));
        final String actualPrivateKey = toUnixEols(creds.getPrivateKey());
        assertEquals(expectedPrivateKey, actualPrivateKey);

        JenkinsRule.WebClient wc = j.createWebClient();
        // Submit works
        j.submit(wc.goTo("configure").getFormByName("config"));

        // config files UI works
        HtmlPage configfiles = wc.goTo("configfiles");
        HtmlPage edit = clickAction(configfiles, "edit");
        //j.interactiveBreak();
        j.submit(edit.getForms().get(1));

        wc.setConfirmHandler(new ConfirmHandler() {
            @Override public boolean handleConfirm(Page page, String s) {
                return true;
            }
        });
        clickAction(configfiles, "remove");

        assertEquals(null, template.getUserData());

        configfiles = wc.goTo("configfiles");
        HtmlPage newOne = configfiles.getAnchorByText("Add a new Config").click();
        HtmlForm form = newOne.getForms().get(1);
        form.getOneHtmlElementByAttribute("input", "value", "jenkins.plugins.openstack.compute.UserDataConfig").click();
        HtmlPage newForm = j.submit(form);
        j.submit(newForm.getForms().get(1));
    }

    private HtmlPage clickAction(HtmlPage configfiles, String action) throws IOException {
        List<HtmlElement> edits = configfiles.getBody().getElementsByAttribute("img", "title", action + " script cloudInit");
        return edits.iterator().next().getEnclosingElement("a").click();
    }

    private String fileAsString(String filename) throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream(getClass().getSimpleName() + "/" + filename));
    }

    @Test
    public void doProvision() throws Exception {
        final JCloudsSlaveTemplate template = j.dummySlaveTemplate("asdf");

        final JCloudsCloud cloudProvision = getCloudWhereUserIsAuthorizedTo(Cloud.PROVISION, template);
        j.executeOnServer(new DoProvision(cloudProvision, template));

        final JCloudsCloud itemConfigure = getCloudWhereUserIsAuthorizedTo(Item.CONFIGURE, template);
        j.executeOnServer(new DoProvision(itemConfigure, template));

        final JCloudsCloud jenkinsRead = getCloudWhereUserIsAuthorizedTo(Jenkins.READ, template);
        try {
            j.executeOnServer(new DoProvision(jenkinsRead, template));
        } catch (AccessDeniedException ex) {
            // Expected
        }
    }

    @Test
    public void expireOpenstackInstanceWhenTokenExpires() throws Exception {
        Openstack.FactoryEP factory = j.mockOpenstackFactory();
        Date justExpired = new Date();
        OSClient.OSClientV2 client = mock(OSClient.OSClientV2.class, RETURNS_DEEP_STUBS);
        when(client.getAccess().getToken().getExpires()).thenReturn(justExpired);
        System.out.println(client.getAccess().getToken().getExpires());
        when(factory.getOpenstack(any(String.class), any(String.class), any(String.class), any(String.class))).thenReturn(new Openstack(client));

        Openstack.Factory.get("", "", "", "");
        Openstack.Factory.get("", "", "", "");
        Openstack.Factory.get("", "", "", "");

        verify(factory, times(3)).getOpenstack(any(String.class), any(String.class), any(String.class), any(String.class));
    }

    @Test
    public void doNotExpireOpenstackInstanceWhenTokenValid() throws Exception {
        Openstack.FactoryEP factory = j.mockOpenstackFactory();
        Date validForAWhile = new Date(System.currentTimeMillis() + 1000 * 60 * 15);
        OSClient.OSClientV2 client = mock(OSClient.OSClientV2.class, RETURNS_DEEP_STUBS);

        when(client.getAccess().getToken().getExpires()).thenReturn(validForAWhile);
        when(factory.getOpenstack(any(String.class), any(String.class), any(String.class), any(String.class))).thenReturn(new Openstack(client));

        Openstack.Factory.get("", "", "", "");
        Openstack.Factory.get("", "", "", "");
        Openstack.Factory.get("", "", "", "");

        verify(factory, times(1)).getOpenstack(any(String.class), any(String.class), any(String.class), any(String.class));
    }

    private JCloudsCloud getCloudWhereUserIsAuthorizedTo(final Permission authorized, final JCloudsSlaveTemplate template) {
        return j.configureSlaveLaunching(new AclControllingJCloudsCloud(template, authorized));
    }

    /**
     * Turns a multi-line string with CR/LF EOLs into one with LF EOLs. Windows
     * and Unix use different end-of-line character sequences, which causes
     * problems when we're comparing strings from difference places, some of
     * which are "native" to the test platform and some are 100% Windows or 100%
     * Unix regardless of the test platform OS.
     */
    private static String toUnixEols(String multiLineString) {
        return multiLineString.replaceAll("\r\n", "\n");
    }

    private static class DoProvision implements Callable<Object> {
        private final JCloudsCloud cloud;
        private final JCloudsSlaveTemplate template;

        public DoProvision(JCloudsCloud cloud, JCloudsSlaveTemplate template) {
            this.cloud = cloud;
            this.template = template;
        }

        @Override public Object call() throws Exception {
            cloud.doProvision(Stapler.getCurrentRequest(), Stapler.getCurrentResponse(), template.name);
            return null;
        }
    }

    private static class AclControllingJCloudsCloud extends PluginTestRule.MockJCloudsCloud {
        private final Permission authorized;

        public AclControllingJCloudsCloud(JCloudsSlaveTemplate template, Permission authorized) {
            super(template);
            this.authorized = authorized;
        }

        @Override public ACL getACL() {
            return new SidACL() {
                @Override protected Boolean hasPermission(Sid p, Permission permission) {
                    return permission.equals(authorized);
                }
            };
        }
    }
}
