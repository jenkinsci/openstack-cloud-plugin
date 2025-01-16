package jenkins.plugins.openstack.compute;

import hudson.slaves.Cloud;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.xml.XmlPage;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.PluginTestRule.NetworkAddress;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static jenkins.plugins.openstack.compute.internal.Openstack.FINGERPRINT_KEY_FINGERPRINT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProvisioningTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void provisionSlaveOnDemand() throws Exception {
        j.jenkins.setNumExecutors(0);
        assertThat(JCloudsComputer.getAll(), emptyIterable());

        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool("custom").build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(opts,"label")));

        FreeStyleProject p = j.createFreeStyleProject();
        // Provision with label
        p.setAssignedLabel(Label.get("label"));
        Node node = j.buildAndAssertSuccess(p).getBuiltOn();
        assertThat(node, Matchers.instanceOf(JCloudsSlave.class));

        JCloudsSlave s = (JCloudsSlave) node;
        Server server = cloud.getOpenstack().getServerById(s.getServerId());
        assertEquals("node:" + server.getName() + ":" + s.getId().getFingerprint(), server.getMetadata().get(ServerScope.METADATA_KEY));

        node.toComputer().doDoDelete();
        if (j.jenkins.getComputer(node.getNodeName()) != null) {
            Thread.sleep(100);
        }
        assertNull("Slave is discarded", j.jenkins.getComputer(node.getNodeName()));

        // Provision without label
        p.setAssignedLabel(null);
        assertThat(j.buildAndAssertSuccess(p).getBuiltOn(), Matchers.instanceOf(JCloudsSlave.class));
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/31")
    public void abortProvisioningWhenOpenstackFails() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        JCloudsCloud cloud = j.dummyCloud(template);
        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenThrow(new Openstack.ActionFailed("It is broken, alright!"));

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        Future<FreeStyleBuild> started = p.scheduleBuild2(0).getStartCondition();

        Thread.sleep(1000);
        assertFalse(started.isDone());

        verify(os, atLeastOnce()).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/37")
    public void detectBootTimingOut() {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        final JCloudsCloud cloud = j.dummyCloud(template);
        Openstack os = cloud.getOpenstack();
        Server server = j.mockServer().name("provisioned").status(Server.Status.BUILD).get();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenCallRealMethod();
        when(os._bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenReturn(server);
        when(os.updateInfo(eq(server))).thenReturn(server);

        try {
            template.provisionServer(null, null);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("Failed to boot server provisioned in time"));
            assertThat(ex.getMessage(), containsString("status=BUILD"));
        }

        verify(os).destroyServer(eq(server));
    }

    @Test
    public void verifyOptionsPropagatedToLauncher() throws Exception {
        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredentials("credid"), "java");
        SlaveOptions expected = j.defaultSlaveOptions().getBuilder().launcherFactory(slaveType).retentionTime(10).build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(
                expected,
                j.dummySlaveTemplate("label"),
                j.dummySlaveTemplate(expected.getBuilder().retentionTime(42).build(), "retention")
        ));

        JCloudsSlave slave = j.provision(cloud, "label");

        SSHLauncher launcher = (SSHLauncher) ((JCloudsLauncher) slave.getLauncher()).getLauncher();
        assertEquals(slave.getPublicAddress(), launcher.getHost());
        assertEquals("credid", launcher.getCredentialsId());
        //noinspection deprecation
        assertEquals("java", launcher.getJavaPath()); // https://github.com/jenkinsci/ssh-slaves-plugin/commit/9d25b12b1340e00d63a069f54c1b2361f745b6fc#commitcomment-49501649
        assertEquals(expected.getJvmOptions(), launcher.getJvmOptions());
        assertEquals(10, (int) slave.getSlaveOptions().getRetentionTime());

        slave = j.provision(cloud, "retention");

        assertEquals(42, (int) slave.getSlaveOptions().getRetentionTime());
    }

    @Test
    public void doProvision() throws Exception {
        JCloudsSlaveTemplate constrained = j.dummySlaveTemplate(j.defaultSlaveOptions().getBuilder().instanceCap(1).build(), "label");
        JCloudsSlaveTemplate free = j.dummySlaveTemplate("free");
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(
                j.defaultSlaveOptions().getBuilder().instanceCap(2).build(),
                constrained, free
        ));

        JenkinsRule.WebClient wc = j.createWebClientAllowingFailures();
        assertThat(
                invokeProvisioning(cloud, wc, "/provision").getWebResponse().getContentAsString(),
                containsString("The slave template name query parameter is missing")
        );
        assertThat(
                invokeProvisioning(cloud, wc, "/provision?name=no_such_template").getWebResponse().getContentAsString(),
                containsString("No such slave template with name : no_such_template")
        );

        // Exceed template quota
        XmlPage provision = invokeProvisioning(cloud, wc, "/provision?name=" + constrained.getName());
        assertThat(provision.getWebResponse().getStatusCode(), equalTo(200));
        while (Jenkins.get().getNodes().isEmpty()) {
            Thread.sleep(500);
        }
        String slaveName = j.jenkins.getNodes().get(0).getNodeName();

        JCloudsSlave slave = (JCloudsSlave) j.jenkins.getNode(slaveName);
        Server server = cloud.getOpenstack().getServerById(slave.getServerId());
        assertEquals("node:" + server.getName() + ":" + slave.getId().getFingerprint(), server.getMetadata().get(ServerScope.METADATA_KEY));

        assertThat(
                invokeProvisioning(cloud, wc, "/provision?name=" + constrained.getName()).getWebResponse().getContentAsString(),
                containsString("Instance cap for this template (openstack/template0) is now reached: 1")
        );

        // Exceed global quota
        provision = invokeProvisioning(cloud, wc, "/provision?name=" + free.getName());
        assertThat(provision.getWebResponse().getStatusCode(), equalTo(200));
        while (Jenkins.get().getNodes().size() == 1) {
            Thread.sleep(500);
        }
        slaveName = null;
        for (Node node : j.jenkins.getNodes()) {
            if (!node.getNodeName().equals(slaveName)) {
                slaveName = node.getNodeName();
            }
        }
        assertNotNull("Slave " +  slaveName+ " should exist", j.jenkins.getNode(slaveName));

        assertThat(
                invokeProvisioning(cloud, wc, "/provision?name=" + free.getName()).getWebResponse().getContentAsString(),
                containsString("Instance cap of openstack is now reached: 2")
        );

        List<ProvisioningActivity> all = CloudStatistics.get().getActivities();
        assertThat(all, Matchers.iterableWithSize(2));
        for (ProvisioningActivity pa : all) {
            waitForCloudStatistics(pa, ProvisioningActivity.Phase.OPERATING);
            assertNotNull(pa.getPhaseExecution(ProvisioningActivity.Phase.OPERATING));
            assertNull(pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED));
            assertEquals(cloud.name, pa.getId().getCloudName());
        }
    }

    private XmlPage invokeProvisioning(JCloudsCloud cloud, JenkinsRule.WebClient wc, String s) throws IOException {
        URL configureUrl = new URL(wc.getContextPath() + "cloud/" + cloud.name + s);
        return wc.getPage(wc.addCrumb(new WebRequest(configureUrl, HttpMethod.POST)));
    }

    @Test
    public void destroyTheServerWhenFipAllocationFails() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool("my_pool").build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();
        when(os.assignFloatingIp(any(Server.class), any(String.class))).thenThrow(new Openstack.ActionFailed("Unable to assign"));

        try {
            template.provisionServer(null, null);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("Unable to assign"));
        }

        waitForAsyncResourceDisposer();

        verify(os, atLeastOnce()).destroyServer(any(Server.class));
    }

    @Test
    public void reflectCloudDeletionInDisposable() throws Exception {
        AsyncResourceDisposer ard = AsyncResourceDisposer.get();
        CloudStatistics cs = CloudStatistics.get();

        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP("foo");
        JCloudsSlave foo = j.provision(cloud, "foo");

        j.jenkins.clouds.remove(cloud);
        j.jenkins.save();

        foo.toComputer().doDoDelete();

        Thread.sleep(1000);
        assertCloudMissingReportedOnce(cs, ard, foo);

        reschedule(ard);

        Thread.sleep(1000);
        assertCloudMissingReportedOnce(cs, ard, foo);
    }

    private void assertCloudMissingReportedOnce(CloudStatistics cs, AsyncResourceDisposer ard, JCloudsSlave foo) {
        final ProvisioningActivity activity = cs.getActivityFor(foo.getId());
        assertThat(activity.getCurrentPhase(), equalTo(ProvisioningActivity.Phase.COMPLETED));
        final List<PhaseExecutionAttachment> attachments = activity.getCurrentPhaseExecution().getAttachments();
        assertThat(attachments, iterableWithSize(1));
        assertThat(attachments.get(0).getDisplayName(), equalTo("Cloud openstack does no longer exists"));
        assertThat(attachments.get(0).getStatus(), equalTo(ProvisioningActivity.Status.WARN));

        assertThat(ard.getBacklog().size(), equalTo(0));
    }

    @Test
    public void correctMetadataSet() throws Exception {
        JCloudsSlaveTemplate template = j.dummySlaveTemplate("label");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(template));

        assertThat(cloud.getOpenstack().instanceUrl(), not(emptyString()));
        assertThat(cloud.getOpenstack().instanceFingerprint(), not(emptyString()));

        Server server = template.provisionServer(null, null);
        Map<String, String> m = server.getMetadata();
        assertEquals(cloud.getOpenstack().instanceUrl(), m.get(Openstack.FINGERPRINT_KEY_URL));
        assertEquals(cloud.getOpenstack().instanceFingerprint(), m.get(FINGERPRINT_KEY_FINGERPRINT));
        assertEquals(cloud.name, m.get(JCloudsSlaveTemplate.OPENSTACK_CLOUD_NAME_KEY));
        assertEquals(template.getName(), m.get(JCloudsSlaveTemplate.OPENSTACK_TEMPLATE_NAME_KEY));
        assertEquals(new ServerScope.Node(server.getName()).getValue(), m.get(ServerScope.METADATA_KEY));
    }

    @Test
    public void timeoutProvisioning() throws Exception {
        JCloudsCloud c = j.dummyCloud(j.dummySlaveTemplate("label"));
        Openstack os = c.getOpenstack();
        when(os._bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenReturn(null); // Timeout
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), anyInt())).thenCallRealMethod();
        Server server = mock(Server.class);
        when(os.getServersByName(anyString())).thenReturn(Collections.singletonList(server));

        for (NodeProvisioner.PlannedNode pn : c.provision(new Cloud.CloudState(Label.get("label"), 0), 1)) {
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
    public void timeoutLaunchingJnlp() throws Exception {
        final SlaveOptions opts = j.defaultSlaveOptions().getBuilder().startTimeout(10).build();
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(opts, j.dummySlaveTemplate("asdf")));
        cloud.setCleanfreq(20); // 20 secondes > 7 sec for timeout
        final Iterable<NodeProvisioner.PlannedNode> pns = cloud.provision(new Cloud.CloudState(Label.get("asdf"), 0), 1);
        assertThat(pns, iterableWithSize(1));
        final PlannedNode pn = pns.iterator().next();
        final Future<Node> pnf = pn.future;

        try {
            Node node = pnf.get();
            fail("Node failed to time out: " + node);
        } catch (ExecutionException ex) {
            assertThat(ex.getCause(), instanceOf(JCloudsCloud.ProvisioningFailedException.class));
            String msg = ex.getCause().getMessage();
            assertThat(msg, containsString("Failed to connect agent"));
            assertThat(msg, containsString("JNLP connection was not established yet"));
            assertThat("Server details are printed", msg, containsString("Server state: Mock for "));
        }

        // Wait for the server to be disposed
        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        while (!disposer.getBacklog().isEmpty()) {
            Thread.sleep(1000);
        }
        verify(cloud.getOpenstack()).destroyServer(any(Server.class));
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/310")
    public void timeoutLaunchingSsh() throws Exception {
        LauncherFactory.SSH sshLaunch = new LauncherFactory.SSH("no-such-creds");
        final SlaveOptions opts = j.defaultSlaveOptions().getBuilder().startTimeout(3000).launcherFactory(sshLaunch).build();
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(opts, j.dummySlaveTemplate("asdf")));
        cloud.setCleanfreq(2);
        JCloudsSlave agent = j.provision(cloud, "asdf");
        JCloudsComputer computer = agent.getComputer();
        assertFalse(agent.isLaunchTimedOut());

        OfflineCause ofc = null;
        for (int i = 0; i < 3; i++){
            ofc = computer.getOfflineCause();
            if (ofc != null) break;
            Thread.sleep(500);
        }
        assertThat(ofc, instanceOf(OfflineCause.LaunchFailed.class));
        // OfflineCause.LaunchFailed is NOT fatal until the stat timeout is up
        assertNull(agent.getFatalOfflineCause());
        assertFalse(agent.isLaunchTimedOut());

        for (int i = 0; i < 4; i++){
            ofc = agent.getFatalOfflineCause();
            if (ofc != null) break;
            Thread.sleep(1000);
        }
        // OfflineCause.LaunchFailed will become fatal when the stat timeout is up
        long aliveFor = System.currentTimeMillis() - agent.getCreatedTime();
        assertTrue("Not timed out after ms " + aliveFor, agent.isLaunchTimedOut());
        //assertThat("Cause not fatal after ms " + aliveFor, computer.getFatalOfflineCause(), instanceOf(OfflineCause.LaunchFailed.class));
        assertThat("Cause not fatal after ms " + aliveFor, ofc, instanceOf(OfflineCause.LaunchFailed.class));

        TimeUnit.SECONDS.sleep(3); // 3 seconds in order to go over cleanFreq
        j.triggerOpenstackSlaveCleanup();

        // Wait for the server to be disposed
        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        while (!disposer.getBacklog().isEmpty()) {
            Thread.sleep(1000);
        }
        verify(cloud.getOpenstack()).destroyServer(any(Server.class));
    }

    @Test
    public void reportOfflineCauseInCloudStats() throws Exception {
        SlaveOptions so = j.defaultSlaveOptions().getBuilder().startTimeout(10000).build(); // 200 for windows test
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(so,"label")));
        cloud.setCleanfreq(30); // clean freq set to 30 secondes not to be run during the test
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();
        slave.toComputer().setTemporarilyOffline(true, new DiskSpaceMonitorDescriptor.DiskSpace("/Fake/it", 42));
        slave.getComputer().deleteSlave();

        ProvisioningActivity pa = CloudStatistics.get().getActivityFor(slave);
        List<PhaseExecutionAttachment> attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        PhaseExecutionAttachment att = attachments.get(0);
        assertEquals("0.000GB left on /Fake/it.", att.getTitle());

        slave = j.provision(cloud, "label");
        Thread.sleep(200); // This relies on being past the startTimeout
        slave.toComputer().setTemporarilyOffline(true, new OfflineCause.ChannelTermination(new RuntimeException("Broken alright")));
        slave.getComputer().deleteSlave();

        pa = CloudStatistics.get().getActivityFor(slave);
        attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        att = attachments.get(0);
        assertThat(att.getTitle(), startsWith("Connection was broken"));

        slave = j.provision(cloud, "label");
        slave.toComputer().disconnect(new OfflineCause.ChannelTermination(new IOException("Broken badly")));
        slave.getComputer().deleteSlave();
        Thread.sleep(200); // This relies on being past the startTimeout

        pa = CloudStatistics.get().getActivityFor(slave);
        attachments = pa.getPhaseExecution(ProvisioningActivity.Phase.COMPLETED).getAttachments();
        assertThat(attachments, Matchers.iterableWithSize(1));
        att = attachments.get(0);
        assertThat(att.getTitle(), startsWith("Connection was broken"));
    }

    @Test
    public void preferFloatingIpv4() throws Exception {
        verifyPreferredAddressUsed("42.42.42.", Arrays.asList(
                NetworkAddress.FIXED_4, NetworkAddress.FIXED_6, NetworkAddress.FLOATING_4, NetworkAddress.FLOATING_6
        ));
    }

    @Test
    public void preferFloatingIpv6() throws Exception {
        verifyPreferredAddressUsed("4242:", Arrays.asList(
                NetworkAddress.FIXED_4, NetworkAddress.FIXED_6, NetworkAddress.FLOATING_6
        ));
    }

    @Test
    public void preferFixedIpv4() throws Exception {
        verifyPreferredAddressUsed("43.43.43.", Arrays.asList(
                NetworkAddress.FIXED_6, NetworkAddress.FIXED_4
        ));
    }

    @Test
    public void findFixedIpv4WhenNoExplicitTypeIsGiven() throws Exception {
        verifyPreferredAddressUsed("43.43.43.", List.of(
                NetworkAddress.FIXED_4_NO_EXPLICIT_TYPE
        ));
    }

    @Test
    public void failIfNoAccessIpFound() {
        try {
            verifyPreferredAddressUsed("Muahaha", Collections.emptyList());
            fail();
        } catch (Exception ex) {
            assertThat(ex.getMessage(), containsString("No access IP address found for "));
        }
    }

    private void verifyPreferredAddressUsed(String expectedAddress, Collection<NetworkAddress> addresses) throws Exception {
        CloudStatistics cs = CloudStatistics.get();
        assertThat(cs.getActivities(), Matchers.iterableWithSize(0));
        j.autoconnectJnlpSlaves();
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(j.dummySlaveTemplate("label")), addresses);
	cloud.setCleanfreq(30);

        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();
        assertThat(computer.buildEnvironment(TaskListener.NULL).get("OPENSTACK_PUBLIC_IP"), startsWith(expectedAddress));
        assertThat(cs.getActivities(), Matchers.iterableWithSize(1));
        assertEquals(computer.getName(), CloudStatistics.get().getActivityFor(computer).getName());

        ProvisioningActivity activity = cs.getActivities().get(0);

        waitForCloudStatistics(activity, ProvisioningActivity.Phase.OPERATING);
        assertThat(activity.getPhaseExecutions().toString(), activity.getCurrentPhase(), equalTo(ProvisioningActivity.Phase.OPERATING));

        Server server = cloud.getOpenstack().getServerById(computer.getNode().getServerId());
        assertEquals("node:" + server.getName() + ":" + computer.getId().getFingerprint(), server.getMetadata().get(ServerScope.METADATA_KEY));

        computer.doDoDelete();
        assertEquals("Slave is discarded", null, j.jenkins.getComputer("provisioned"));
        waitForCloudStatistics(activity, ProvisioningActivity.Phase.COMPLETED);
        assertThat(activity.getCurrentPhase(), equalTo(ProvisioningActivity.Phase.COMPLETED));
    }

    /**
     * Waits for CloudStatistics to catch up with the test thread.
     * CloudStatistics runs asynchronously, so we have to wait for it to catch
     * up so that the tests run reliably.
     */
    private static void waitForCloudStatistics(ProvisioningActivity activityToWaitFor,
            ProvisioningActivity.Phase expectedPhase) throws InterruptedException {
        final int millisecondsToWaitBetweenPolls = 100;
        final int maxTimeToWaitInMilliseconds = 20000;
        final long timestampBeforeWaiting = System.nanoTime();
        while (true) {
            ProvisioningActivity.Phase current = activityToWaitFor.getCurrentPhase();
            if (Objects.equals(current, expectedPhase)) {
                return; // cloud statistics have updated
            }
            final long timestampNow = System.nanoTime();
            final long timeSpentWaitingInNanoseconds = timestampNow - timestampBeforeWaiting;
            final long timeSpentWaitingInMilliseconds = timeSpentWaitingInNanoseconds / 1000000L;
            final long timeToWaitRemainingInMilliseconds = maxTimeToWaitInMilliseconds - timeSpentWaitingInMilliseconds;
            if (timeToWaitRemainingInMilliseconds <= 0L) {
                fail("Timed out waiting " + timeSpentWaitingInMilliseconds + " milliseconds, for " + activityToWaitFor
                        + " to get into " + expectedPhase + " phase. Actually in " + current
                );
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
        reschedule(disposer);
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

    @SuppressWarnings("deprecation")
    private static void reschedule(AsyncResourceDisposer disposer) {
        disposer.reschedule();
    }
}
