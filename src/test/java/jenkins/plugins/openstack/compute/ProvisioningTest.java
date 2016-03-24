package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequestSettings;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.ArgumentCaptor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * @author ogondza.
 */
public class ProvisioningTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test @SuppressWarnings("deprecation")
    public void manuallyProvisionAndKill() throws Exception {
        Computer computer = j.provisionDummySlave("label").toComputer();
        assertTrue("Slave should be connected", computer.isOnline());
        assertThat(computer.buildEnvironment(TaskListener.NULL).get("OPENSTACK_PUBLIC_IP"), startsWith("42.42.42."));

        computer.doDoDelete();
        assertTrue("Slave is temporarily offline", computer.isTemporarilyOffline());

        j.triggerOpenstackSlaveCleanup();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
    }

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        JCloudsCloud cloud = j.createCloudLaunchingDummySlaves("label");

        Computer[] originalComputers = j.jenkins.getComputers();

        j.jenkins.setNumExecutors(0);
        FreeStyleProject p = j.createFreeStyleProject();
        // Provision with label
        p.setAssignedLabel(Label.get("label"));
        Node node = j.buildAndAssertSuccess(p).getBuiltOn();
        assertThat(node, Matchers.instanceOf(JCloudsSlave.class));
        node.toComputer().doDoDelete();
        j.triggerOpenstackSlaveCleanup();
        assertThat(originalComputers, arrayContainingInAnyOrder(j.jenkins.getComputers()));

        // Provision without label
        p.setAssignedLabel(null);
        assertThat(j.buildAndAssertSuccess(p).getBuiltOn(), Matchers.instanceOf(JCloudsSlave.class));

        Openstack os = cloud.getOpenstack();
        verify(os, atLeastOnce()).getRunningNodes();
        verify(os, times(2)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        verify(os, times(2)).assignFloatingIp(any(Server.class), eq("custom"));
        verify(os, times(2)).updateInfo(any(Server.class));
        verify(os, atLeastOnce()).destroyServer(any(Server.class));

        verifyNoMoreInteractions(os);
    }

    @Test
    public void doNotProvisionOnceInstanceCapReached() {
        JCloudsSlaveTemplate restricted = j.dummySlaveTemplate(SlaveOptions.builder().instanceCap(1).build(), "restricted");
        JCloudsSlaveTemplate open = j.dummySlaveTemplate(SlaveOptions.builder().instanceCap(null).build(), "open");
        JCloudsCloud cloud = j.dummyCloud(SlaveOptions.builder().instanceCap(4).build(), restricted, open);

        Server restrictedMachine = j.mockServer().metadataItem(JCloudsSlaveTemplate.OPENSTACK_TEMPLATE_NAME_KEY, restricted.name).get();
        Server openMachine = j.mockServer().metadataItem(JCloudsSlaveTemplate.OPENSTACK_TEMPLATE_NAME_KEY, open.name).get();

        List<Server> running = new ArrayList<>();

        when(cloud.getOpenstack().getRunningNodes()).thenReturn(running);

        // Template quota exceeded
        assertEquals(1, cloud.provision(Label.get("restricted"), 2).size());
        running.add(restrictedMachine);
        assertEquals(0, cloud.provision(Label.get("restricted"), 1).size());
        assertEquals(1, restricted.getRunningNodes().size());

        // Cloud quota exceeded
        assertEquals(2, cloud.provision(Label.get("open"), 2).size());
        running.add(openMachine);
        running.add(openMachine);
        assertEquals(2, open.getRunningNodes().size());
        assertEquals(1, cloud.provision(Label.get("open"), 2).size());
        running.add(openMachine);
        assertEquals(3, open.getRunningNodes().size());

        assertEquals(0, cloud.provision(Label.get("restricted"), 1).size());
        assertEquals(0, cloud.provision(Label.get("open"), 1).size());
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
                new URL(wc.getContextPath() + "cloud/openstack/provision?name=" + template.name),
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
            template.provision(cloud);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("Failed to boot server in time"));
            assertThat(ex.getMessage(), containsString("status=BUILD"));
        }

        verify(os).destroyServer(eq(server));
    }

    @Test
    public void verifyOptionsPropagatedToLauncher() throws Exception {
        SlaveOptions expected = j.dummySlaveOptions().getBuilder().slaveType(JCloudsCloud.SlaveType.SSH).retentionTime(10).build();
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(
                expected,
                j.dummySlaveTemplate("label"),
                j.dummySlaveTemplate(expected.getBuilder().retentionTime(42).build(), "retention")
        ));

        JCloudsSlave slave = j.provision(cloud, "label");

        SSHLauncher launcher = (SSHLauncher) ((JCloudsLauncher) slave.getLauncher()).launcher;
        assertEquals(slave.getPublicAddress(), launcher.getHost());
        assertEquals(expected.getCredentialsId(), launcher.getCredentialsId());
        assertEquals(expected.getJvmOptions(), launcher.getJvmOptions());
        assertEquals(10, ((JCloudsComputer) slave.toComputer()).getRetentionTime());

        slave = j.provision(cloud, "retention");

        assertEquals(42, ((JCloudsComputer) slave.toComputer()).getRetentionTime());
    }

    @Test
    public void allowToUseImageNameAsWellAsId() throws Exception {
        SlaveOptions opts = j.dummySlaveOptions().getBuilder().imageId("image-id").build();
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(opts, "label")));

        Openstack os = cloud.getOpenstack();
        // simulate same image resolved to different ids
        when(os.getImageIdFor(eq("image-id"))).thenReturn("image-id", "something-else");

        j.provision(cloud, "label"); j.provision(cloud, "label");

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(2)).bootAndWaitActive(captor.capture(), any(Integer.class));

        List<ServerCreateBuilder> builders = captor.getAllValues();
        assertEquals(2, builders.size());
        assertEquals("image-id", builders.get(0).build().getImageRef());
        assertEquals("something-else", builders.get(1).build().getImageRef());
    }
}
