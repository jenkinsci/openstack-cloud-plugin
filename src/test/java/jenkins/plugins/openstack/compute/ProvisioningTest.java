package jenkins.plugins.openstack.compute;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.OfflineCause;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.BlockDeviceMappingCreate;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.compute.domain.NovaBlockDeviceMappingCreate;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * @author ogondza.
 */
public class ProvisioningTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void manuallyProvisionAndKill() throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        assertThat(cs.getActivities(), Matchers.iterableWithSize(0));

        JCloudsCloud cloud = j.createCloudLaunchingDummySlaves("label");
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        computer.waitUntilOnline();
        assertThat(computer.buildEnvironment(TaskListener.NULL).get("OPENSTACK_PUBLIC_IP"), startsWith("42.42.42."));
        assertEquals(computer.getName(), CloudStatistics.get().getActivityFor(computer).getName());

        assertThat(cs.getActivities(), Matchers.iterableWithSize(1));
        ProvisioningActivity activity = cs.getActivities().get(0);

        waitForCloudStatistics(activity, ProvisioningActivity.Phase.OPERATING);
        assertThat(activity.getPhaseExecutions().toString(), activity.getCurrentPhase(), equalTo(ProvisioningActivity.Phase.OPERATING));

        Server server = cloud.getOpenstack().getServerById(computer.getNode().getServerId());
        assertEquals("node:" + server.getName(), server.getMetadata().get(ServerScope.METADATA_KEY));

        computer.doDoDelete();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
        waitForCloudStatistics(activity, ProvisioningActivity.Phase.COMPLETED);
        assertThat(activity.getCurrentPhase(), equalTo(ProvisioningActivity.Phase.COMPLETED));
    }

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        j.jenkins.setNumExecutors(0);
        Computer[] originalComputers = j.jenkins.getComputers();
        assertThat(originalComputers, arrayWithSize(1)); // Only master expected

        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool("custom").build();
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(opts,"label")));

        FreeStyleProject p = j.createFreeStyleProject();
        // Provision with label
        p.setAssignedLabel(Label.get("label"));
        Node node = j.buildAndAssertSuccess(p).getBuiltOn();
        assertThat(node, Matchers.instanceOf(JCloudsSlave.class));

        Server server = cloud.getOpenstack().getServerById(((JCloudsSlave) node).getServerId());
        assertEquals("node:" + server.getName(), server.getMetadata().get(ServerScope.METADATA_KEY));

        node.toComputer().doDoDelete();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer(node.getNodeName()));

        // Provision without label
        p.setAssignedLabel(null);
        assertThat(j.buildAndAssertSuccess(p).getBuiltOn(), Matchers.instanceOf(JCloudsSlave.class));

        Openstack os = cloud.getOpenstack();
        verify(os, atLeastOnce()).getRunningNodes();
        verify(os, times(2)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        verify(os, times(2)).assignFloatingIp(any(Server.class), eq("custom"));
        verify(os, times(2)).updateInfo(any(Server.class));
        verify(os, atLeastOnce()).destroyServer(any(Server.class));

        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertThat(activities, Matchers.iterableWithSize(2));
    }

    @Test
    public void doNotProvisionOnceInstanceCapReached() throws Exception {
        SlaveOptions init = j.defaultSlaveOptions();
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

        cloud.getOpenstack().destroyServer(openTmplt.getRunningNodes().get(0));
        assertEquals(3, cloud.getOpenstack().getRunningNodes().size());

        // Choose the available one when multiple options
        assertProvisioned(1, cloud.provision(Label.get("common"), 1));
        assertEquals(4, cloud.getOpenstack().getRunningNodes().size());
        assertEquals(1, restrictedTmplt.getRunningNodes().size());
        assertEquals(3, openTmplt.getRunningNodes().size());
    }

    private void assertProvisioned(int expectedCount, Collection<NodeProvisioner.PlannedNode> nodes) throws Exception {
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

        JenkinsRule.WebClient wc = j.createWebClientAllowingFailures();
        Page page = wc.getPage(wc.addCrumb(new WebRequest(
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
            assertThat(ex.getMessage(), containsString("Failed to boot server provisioned in time"));
            assertThat(ex.getMessage(), containsString("status=BUILD"));
        }

        verify(os).destroyServer(eq(server));
    }

    @Test
    public void verifyOptionsPropagatedToLauncher() throws Exception {
        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredential("credid"), "java");
        SlaveOptions expected = j.defaultSlaveOptions().getBuilder().launcherFactory(slaveType).retentionTime(10).build();
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(
                expected,
                j.dummySlaveTemplate("label"),
                j.dummySlaveTemplate(expected.getBuilder().retentionTime(42).build(), "retention")
        ));

        JCloudsSlave slave = j.provision(cloud, "label");

        SSHLauncher launcher = (SSHLauncher) ((JCloudsLauncher) slave.getLauncher()).getLauncher();
        assertEquals(slave.getPublicAddress(), launcher.getHost());
        assertEquals("credid", launcher.getCredentialsId());
        assertEquals("java", launcher.getJavaPath());
        assertEquals(expected.getJvmOptions(), launcher.getJvmOptions());
        assertEquals(10, (int) slave.getSlaveOptions().getRetentionTime());

        slave = j.provision(cloud, "retention");

        assertEquals(42, (int) slave.getSlaveOptions().getRetentionTime());
    }

    @Test
    public void doProvision() throws Exception {
        JCloudsSlaveTemplate constrained = j.dummySlaveTemplate(j.defaultSlaveOptions().getBuilder().instanceCap(1).build(), "label");
        JCloudsSlaveTemplate free = j.dummySlaveTemplate("free");
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(
                j.defaultSlaveOptions().getBuilder().instanceCap(2).build(),
                constrained, free
        ));

        JenkinsRule.WebClient wc = j.createWebClientAllowingFailures();
        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision").getWebResponse().getContentAsString(),
                containsString("The slave template name query parameter is missing")
        );
        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=no_such_template").getWebResponse().getContentAsString(),
                containsString("No such slave template with name : no_such_template")
        );

        // Exceed template quota
        HtmlPage provision = wc.goTo("cloud/" + cloud.name + "/provision?name=" + constrained.name);
        assertThat(provision.getWebResponse().getStatusCode(), equalTo(200));
        String slaveName = extractNodeNameFomUrl(provision);
        assertNotNull("Slave " +  slaveName+ " should exist", j.jenkins.getNode(slaveName));

        Server server = cloud.getOpenstack().getServerById(((JCloudsSlave) j.jenkins.getNode(slaveName)).getServerId());
        assertEquals("node:" + server.getName(), server.getMetadata().get(ServerScope.METADATA_KEY));

        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=" + constrained.name).getWebResponse().getContentAsString(),
                containsString("Instance cap for this template (openstack/template0) is now reached: 1")
        );

        // Exceed global quota
        provision = wc.goTo("cloud/" + cloud.name + "/provision?name=" + free.name);
        assertThat(provision.getWebResponse().getStatusCode(), equalTo(200));
        slaveName = extractNodeNameFomUrl(provision);
        assertNotNull("Slave " +  slaveName+ " should exist", j.jenkins.getNode(slaveName));

        assertThat(
                wc.goTo("cloud/" + cloud.name + "/provision?name=" + free.name).getWebResponse().getContentAsString(),
                containsString("Instance cap of openstack is now reached: 2")
        );

        List<ProvisioningActivity> all = CloudStatistics.get().getActivities();
        assertThat(all, Matchers.iterableWithSize(2));
        for (ProvisioningActivity pa : all) {
            waitForCloudStatistics(pa, ProvisioningActivity.Phase.OPERATING);
            assertNotNull(pa.getPhaseExecution(ProvisioningActivity.Phase.OPERATING));
            assertNull(pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED));
            assertNotNull(j.jenkins.getComputer(pa.getName()));
            assertEquals(cloud.name, pa.getId().getCloudName());
        }
    }

    private String extractNodeNameFomUrl(HtmlPage provision) throws MalformedURLException {
        return provision.getFullyQualifiedUrl("").toExternalForm().replaceAll("^.*/(.*)/$", "$1");
    }

    @Test
    public void useSeveralTemplatesToProvisionInOneBatchWhenTemplateInstanceCapExceeded() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().instanceCap(1).build();
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

    @Test
    public void destroyTheServerWhenFipAllocationFails() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool("my_pool").build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();
        when(os.assignFloatingIp(any(Server.class), any(String.class))).thenThrow(new Openstack.ActionFailed("Unable to assign"));

        try {
            template.provision(cloud);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("Unable to assign"));
        }

        waitForAsyncResourceDisposer();

        verify(os).destroyServer(any(Server.class));
    }

    @Test
    public void correctMetadataSet() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));

        Server server = template.provision(cloud);
        Map<String, String> m = server.getMetadata();

        assertEquals(j.getURL().toExternalForm(), m.get(Openstack.FINGERPRINT_KEY));
        assertEquals(cloud.name, m.get(JCloudsSlaveTemplate.OPENSTACK_CLOUD_NAME_KEY));
        assertEquals(template.name, m.get(JCloudsSlaveTemplate.OPENSTACK_TEMPLATE_NAME_KEY));
        assertEquals(new ServerScope.Node(server.getName()).getValue(), m.get(ServerScope.METADATA_KEY));
    }

    @Test
    public void timeoutProvisioning() throws Exception {
        JCloudsCloud c = j.dummyCloud(j.dummySlaveTemplate("label"));
        Openstack os = c.getOpenstack();
        when(os._bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenReturn(null); // Timeout
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenCallRealMethod();
        Server server = mock(Server.class);
        when(os.getServersByName(any(String.class))).thenReturn(Collections.singletonList(server));

        for (NodeProvisioner.PlannedNode pn : c.provision(Label.get("label"), 1)) {
            try {
                pn.future.get();
                fail();
            } catch (ExecutionException ex) {
                Throwable e = ex.getCause();
                assertThat(e, instanceOf(Openstack.ActionFailed.class));
                assertThat(e.getMessage(), containsString("Failed to provision the"));
                assertThat(e.getMessage(), containsString("in time"));
            }
        }

        verify(os).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os)._bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os).destroyServer(eq(server));
    }

    @Test
    public void timeoutLaunching() throws Exception {
        final SlaveOptions opts = j.defaultSlaveOptions().getBuilder().startTimeout(1000).build();
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(opts, j.dummySlaveTemplate("asdf")));
        final Iterable<NodeProvisioner.PlannedNode> pns = cloud.provision(Label.get("asdf"), 1);
        final Matcher<Iterable<NodeProvisioner.PlannedNode>> hasOnlyOneElement = iterableWithSize(1);
        assertThat(pns, hasOnlyOneElement);
        final PlannedNode pn = pns.iterator().next();
        final Future<Node> pnf = pn.future;

        try {
            pnf.get();
            fail();
        } catch (ExecutionException ex) {
            assertThat(ex.getCause(), instanceOf(JCloudsCloud.ProvisioningFailedException.class));
            String msg = ex.getCause().getMessage();
            assertThat(msg, containsString("Failed to connect agent"));
            assertThat(msg, containsString("JNLP connection was not established yet"));
            assertThat("Server details are printed", msg, containsString("Server state: Mock for "));
        }

        verify(cloud.getOpenstack()).destroyServer(any(Server.class));
    }

    @Test
    public void reportOfflineCauseInCloudStats() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate("label")));
        JCloudsSlave slave = j.provision(cloud, "label");
        slave.toComputer().setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/Fake/it", 42));
        ((JCloudsComputer) slave.toComputer()).deleteSlave();

        ProvisioningActivity pa = CloudStatistics.get().getActivityFor(slave);
        List<PhaseExecutionAttachment> attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        PhaseExecutionAttachment att = attachments.get(0);
        assertEquals("Disk space is too low. Only 0.000GB left on /Fake/it.", att.getTitle());

        slave = j.provision(cloud, "label");
        slave.toComputer().setTemporarilyOffline(true, new OfflineCause.ChannelTermination(new RuntimeException("Broken alright")));
        ((JCloudsComputer) slave.toComputer()).deleteSlave();

        pa = CloudStatistics.get().getActivityFor(slave);
        attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        att = attachments.get(0);
        assertThat(att.getTitle(), startsWith("Connection was broken: java.lang.RuntimeException: Broken alright"));

        slave = j.provision(cloud, "label");
        slave.toComputer().disconnect(new OfflineCause.ChannelTermination(new IOException("Broken badly")));
        ((JCloudsComputer) slave.toComputer()).deleteSlave();

        pa = CloudStatistics.get().getActivityFor(slave);
        attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        att = attachments.get(0);
        assertThat(att.getTitle(), startsWith("Connection was broken: java.io.IOException: Broken badly"));
    }

    /**
     * Waits for CloudStatistics to catch up with the test thread.
     * CloudStatistics runs asynchronously, so we have to wait for it to catch
     * up so that the tests run reliably.
     */
    private static void waitForCloudStatistics(ProvisioningActivity activityToWaitFor,
            ProvisioningActivity.Phase expectedPhase) throws InterruptedException {
        final int millisecondsToWaitBetweenPolls = 100;
        final int maxTimeToWaitInMilliseconds = 5000;
        final long timestampBeforeWaiting = System.nanoTime();
        while (true) {
            final Object actual = activityToWaitFor.getPhaseExecution(expectedPhase);
            if (actual != null) {
                return; // cloud statistics have updated
            }
            final long timestampNow = System.nanoTime();
            final long timeSpentWaitingInNanoseconds = timestampNow - timestampBeforeWaiting;
            final long timeSpentWaitingInMilliseconds = timeSpentWaitingInNanoseconds / 1000000L;
            final long timeToWaitRemainingInMilliseconds = maxTimeToWaitInMilliseconds - timeSpentWaitingInMilliseconds;
            if (timeToWaitRemainingInMilliseconds <= 0L) {
                assertNotNull("After waiting " + timeSpentWaitingInMilliseconds + " milliseconds, calling "
                        + ProvisioningActivity.class.getSimpleName() + ".getPhaseExecution(" + expectedPhase + ") on "
                        + activityToWaitFor + " still returned null", actual);
            }
            final long timeToWaitNowInMilliseconds = Math.min(millisecondsToWaitBetweenPolls,
                    timeToWaitRemainingInMilliseconds);
            Thread.sleep(timeToWaitNowInMilliseconds);
        }
    }

    /**
     * Waits for AsyncResourceDisposer to catch up with the test thread.
     * AsyncResourceDisposer runs asynchronously, so we have to wait for it to
     * catch up so that the tests run reliably.
     */
    private static void waitForAsyncResourceDisposer() throws InterruptedException {
        final int millisecondsToWaitBetweenPolls = 100;
        final int maxTimeToWaitInMilliseconds = 5000;
        final AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        //noinspection deprecation
        disposer.reschedule();
        final long timestampBeforeWaiting = System.nanoTime();
        while (true) {
            final Set<?> actual = disposer.getBacklog();
            if (actual.isEmpty()) {
                return; // all resources disposed of
            }
            final long timestampNow = System.nanoTime();
            final long timeSpentWaitingInNanoseconds = timestampNow - timestampBeforeWaiting;
            final long timeSpentWaitingInMilliseconds = timeSpentWaitingInNanoseconds / 1000000L;
            final long timeToWaitRemainingInMilliseconds = maxTimeToWaitInMilliseconds - timeSpentWaitingInMilliseconds;
            if (timeToWaitRemainingInMilliseconds <= 0L) {
                assertThat("After waiting " + timeSpentWaitingInMilliseconds + " milliseconds, "
                        + AsyncResourceDisposer.class.getSimpleName() + ".getBacklog()", actual, empty());
            }
            final long timeToWaitNowInMilliseconds = Math.min(millisecondsToWaitBetweenPolls,
                    timeToWaitRemainingInMilliseconds);
            Thread.sleep(timeToWaitNowInMilliseconds);
        }
    }
}
