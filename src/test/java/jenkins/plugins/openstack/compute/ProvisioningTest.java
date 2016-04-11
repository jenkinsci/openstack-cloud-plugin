package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.iterableWithSize;
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
import com.gargoylesoftware.htmlunit.WebResponse;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProvisioner;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Matcher;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
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
        Thread.sleep(500);
        assertThat(j.jenkins.getComputers(), arrayContainingInAnyOrder(originalComputers));

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
    public void doNotProvisionOnceInstanceCapReached() throws Exception {
        SlaveOptions init = j.dummySlaveOptions();
        JCloudsSlaveTemplate restrictedTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(1).build(), "restricted common");
        JCloudsSlaveTemplate openTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(null).build(), "open common");
        JCloudsCloud cloud = j.dummyCloud(init.getBuilder().instanceCap(4).build(), restrictedTmplt, openTmplt);
        j.configureSlaveLaunching(cloud);

        Label restricted = Label.get("restricted");
        Label open = Label.get("open");

        // Template quota exceeded
        assertProvisioned(1, cloud.provision(restricted, 2));
        assertEquals(1, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(1, restrictedTmplt.getRunningNodes().size());

        assertProvisioned(0, cloud.provision(restricted, 1));
        assertEquals(1, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(1, restrictedTmplt.getRunningNodes().size());

        // Cloud quota exceeded
        assertProvisioned(2, cloud.provision(open, 2));
        assertEquals(3, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(2, openTmplt.getRunningNodes().size());

        assertProvisioned(1, cloud.provision(open, 2));
        assertEquals(4, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(3, openTmplt.getRunningNodes().size());

        // Both exceeded
        assertProvisioned(0, cloud.provision(restricted, 1));
        assertProvisioned(0, cloud.provision(open, 1));
        assertEquals(4, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(1, restrictedTmplt.getRunningNodes().size());
        assertEquals(3, openTmplt.getRunningNodes().size());

        open.getNodes().iterator().next().toComputer().doDoDelete();
        j.triggerOpenstackSlaveCleanup();
        assertEquals(3, cloud.getOpenstack().getRunningNodes().size());

        // Choose the available one when multiple options
        assertProvisioned(1, cloud.provision(Label.get("common"), 1));
        assertEquals(4, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(1, restrictedTmplt.getRunningNodes().size());
        assertEquals(3, openTmplt.getRunningNodes().size());
    }

    public void assertProvisioned(int expectedCount, Collection<NodeProvisioner.PlannedNode> nodes) throws Exception {
        assertEquals(expectedCount, nodes.size());
        for (NodeProvisioner.PlannedNode node : nodes) {
            node.future.get();
        }
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

    @Test
    public void doProvision() throws Exception {
        JCloudsSlaveTemplate constrained = j.dummySlaveTemplate(j.dummySlaveOptions().getBuilder().instanceCap(1).build(), "label");
        JCloudsSlaveTemplate free = j.dummySlaveTemplate("free");
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(
                j.dummySlaveOptions().getBuilder().instanceCap(2).build(),
                constrained, free
        ));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.setPrintContentOnFailingStatusCode(false);
        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision").getWebResponse().getContentAsString(),
                containsString("The slave template name query parameter is missing")
        );
        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=no_such_template").getWebResponse().getContentAsString(),
                containsString("No such slave template with name : no_such_template")
        );

        j.provision(cloud, "label"); // Exceed template quota

        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=" + constrained.name).getWebResponse().getContentAsString(),
                containsString("Instance cap for this template (openstack/template0) is now reached: 1")
        );

        j.provision(cloud, "free"); // Exceed global quota

        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=" + free.name).getWebResponse().getContentAsString(),
                containsString("Instance cap of openstack is now reached: 2")
        );
    }

    @Test
    public void useSeveralTemplatesToProvisionInOneBatchWhenTemplateInstanceCapExceeded() throws Exception {
        SlaveOptions opts = j.dummySlaveOptions().getBuilder().instanceCap(1).build();
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(
                j.dummySlaveTemplate(opts, "label 1"),
                j.dummySlaveTemplate(opts, "label 2"),
                j.dummySlaveTemplate(opts, "label 3")
        ));


        Collection<NodeProvisioner.PlannedNode> plan = cloud.provision(Label.get("label"), 4);
        assertEquals(3, plan.size());

        int cntr = 1;
        for (NodeProvisioner.PlannedNode pn: plan) {
            LabelAtom expectedLabel = LabelAtom.get(String.valueOf(cntr));

            Set<LabelAtom> assignedLabels = pn.future.get().getAssignedLabels();
            assertTrue(assignedLabels.toString(), assignedLabels.contains(expectedLabel));
            cntr++;
        }
    }
}
