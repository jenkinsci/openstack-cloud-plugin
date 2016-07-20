package jenkins.plugins.openstack;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import jenkins.plugins.openstack.compute.SlaveOptions;
import jenkins.plugins.openstack.compute.UserDataConfig;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.compute.domain.NovaAddresses;
import org.openstack4j.openstack.compute.domain.NovaAddresses.NovaAddress;

import hudson.Extension;
import hudson.Launcher.LocalLauncher;
import hudson.Proc;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Which;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.StreamTaskListener;
import jenkins.plugins.openstack.compute.JCloudsCleanupThread;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlave;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.internal.Openstack;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Test utils for plugin functional testing.
 *
 * @author ogondza
 */
public final class PluginTestRule extends JenkinsRule {

    private static final Random rnd = new Random();
    private final AtomicInteger slaveCount = new AtomicInteger(0);
    private final AtomicInteger templateCount = new AtomicInteger(0);

    private final Map<String, Proc> slavesToKill = new HashMap<>();

    public SlaveOptions dummySlaveOptions() {
        ConfigProvider.all().get(UserDataConfig.UserDataConfigProvider.class).save(new Config("dummyUserDataId", "Fake", "It is a fake", "Fake content"));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, "dummyCredentialId", "john", null, null, "Description")
        );
        // Use some real-looking values preserving defaults to make sure plugin works with them
        return getCloudDescriptor().getDefaultOptions().getBuilder()
                .imageId("dummyImageId")
                .hardwareId("dummyHardwareId")
                .networkId("dummyNetworkId")
                .userDataId("dummyUserDataId")
                .floatingIpPool("dummyPoolName")
                .availabilityZone("dummyAvailabilityZone")
                .keyPairName("dummyKeyPairName")
                .jvmOptions("dummyJvmOptions")
                .credentialsId("dummyCredentialId")
                .slaveType(JCloudsCloud.SlaveType.JNLP)
                .build()
        ;
    }

    public void autoconnectJnlpSlaves() {
        JnlpAutoConnect launcher = jenkins.getExtensionList(ComputerListener.class).get(JnlpAutoConnect.class);
        launcher.rule = this;
    }

    /**
     * Connect slave to JNLP launched computer.
     *
     * The rule will kill it after the test if not killed already.
     */
    public Proc connectJnlpSlave(String slaveName) throws IOException, InterruptedException {
        if (slavesToKill.get(slaveName) != null) {
            throw new IOException("Connecting JNLP slave that is already running: " + jenkins.getComputer(slaveName).getSystemProperties().get("startedBy"));
        }
        File jar = Which.jarFile(Channel.class);
        String url = getURL() + "computer/" + slaveName + "/slave-agent.jnlp";
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        String java = System.getProperty("java.home") + "/bin/java";
        Proc proc = new LocalLauncher(listener).launch()
                .cmds(java, "-jar", "-DstartedBy=" + getTestDescription(), jar.getAbsolutePath(), "-jnlpUrl", url)
                .stderr(System.err)
                .stdout(System.out)
                .start()
        ;
        slavesToKill.put(slaveName, proc);
        return proc;
    }

    /**
     * Force idle slave cleanup now.
     */
    public void triggerOpenstackSlaveCleanup() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(JCloudsCleanupThread.class).execute(TaskListener.NULL);
    }

    public JCloudsSlaveTemplate dummySlaveTemplate(String labels) {
        return dummySlaveTemplate(SlaveOptions.empty(), labels);
    }

    public JCloudsSlaveTemplate dummySlaveTemplate(SlaveOptions opts, String labels) {
        int num = templateCount.getAndIncrement();
        return new JCloudsSlaveTemplate("template" + num, labels, opts);
    }

    public JCloudsCloud dummyCloud(JCloudsSlaveTemplate... templates) {
        JCloudsCloud cloud = new MockJCloudsCloud(templates);
        jenkins.clouds.add(cloud);
        return cloud;
    }

    public JCloudsCloud dummyCloud(SlaveOptions opts, JCloudsSlaveTemplate... templates) {
        JCloudsCloud cloud = new MockJCloudsCloud(opts, templates);
        jenkins.clouds.add(cloud);
        return cloud;
    }

    public JCloudsCloud createCloudLaunchingDummySlaves(String labels) {
        return configureSlaveLaunching(dummyCloud(dummySlaveTemplate(labels)));
    }

    public JCloudsCloud configureSlaveLaunching(JCloudsCloud cloud) {
        autoconnectJnlpSlaves();
        return configureSlaveProvisioning(cloud);
    }

    /**
     * The provisioning future will never complete as it will wait for launch.
     */
    public JCloudsCloud configureSlaveProvisioning(JCloudsCloud cloud) {
        if (cloud.getTemplates().size() == 0) throw new Error("Unable to provision - no templates provided");

        final List<Server> running = new ArrayList<>();

        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                ServerCreateBuilder builder = (ServerCreateBuilder) invocation.getArguments()[0];
                int num = slaveCount.getAndIncrement();
                Server machine = mockServer()
                        .name("provisioned" + num)
                        .floatingIp("42.42.42." + num)
                        .metadata(builder.build().getMetaData())
                        .get()
                ;
                synchronized (running) {
                    running.add(machine);
                }
                return machine;
            }
        });
        when(os.updateInfo(any(Server.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                return (Server) invocation.getArguments()[0];
            }
        });
        when(os.getRunningNodes()).thenAnswer(new Answer<List<Server>>() {
            @Override public List<Server> answer(InvocationOnMock invocation) throws Throwable {
                synchronized (running) {
                    return new ArrayList<>(running);
                }
            }
        });
        when(os.getServerById(any(String.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                String expected = (String) invocation.getArguments()[0];
                synchronized (running) {
                    for (Server s: running) {
                        if (expected.equals(s.getId())) {
                            return s;
                        }
                    }
                }

                return null;
            }
        });
        doAnswer(new Answer() {
            @Override public Object answer(InvocationOnMock invocation) throws Throwable {
                Server server = (Server) invocation.getArguments()[0];
                running.remove(server);
                return null;
            }
        }).when(os).destroyServer(any(Server.class));
        return cloud;
    }

    public JCloudsSlave provision(JCloudsCloud cloud, String label) throws ExecutionException, InterruptedException {
        Collection<PlannedNode> slaves = cloud.provision(Label.get(label), 1);
        if (slaves.size() != 1) throw new AssertionError("One slave expected to be provisioned, was " + slaves.size());

        PlannedNode plannedNode = slaves.iterator().next();

        // Simulate what NodeProvisioner does.
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
            cl.onStarted(cloud, Label.get(label), slaves);
        }
        try {
            JCloudsSlave slave = (JCloudsSlave) plannedNode.future.get();
            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                cl.onComplete(plannedNode, slave);
            }
            return slave;
        } catch (Throwable ex) {
            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                cl.onFailure(plannedNode, ex);
            }
            throw ex;
        }
    }

    public JCloudsSlave provisionDummySlave(String labels) throws InterruptedException, ExecutionException {
        JCloudsCloud cloud = createCloudLaunchingDummySlaves(labels);
        return provision(cloud, labels);
    }

    public Openstack fakeOpenstackFactory() {
        return fakeOpenstackFactory(mock(Openstack.class, RETURNS_SMART_NULLS));
    }

    @SuppressWarnings("deprecation")
    public Openstack fakeOpenstackFactory(final Openstack os) {
        ExtensionList.lookup(Openstack.FactoryEP.class).add(new Openstack.FactoryEP() {
            @Override
            protected @Nonnull Openstack getOpenstack(
                    @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String project, @CheckForNull String domain, @CheckForNull String region, @CheckForNull String zone
            ) throws FormValidation {
                return os;
            }
        });
        return os;
    }

    public MockServerBuilder mockServer() {
        return new MockServerBuilder();
    }

    public JCloudsCloud.DescriptorImpl getCloudDescriptor() {
        return jenkins.getDescriptorByType(JCloudsCloud.DescriptorImpl.class);
    }

    public class MockServerBuilder {

        private final Server server;
        private final Map<String, String> metadata = new HashMap<>();

        public MockServerBuilder() {
            server = mock(Server.class);
            when(server.getId()).thenReturn(UUID.randomUUID().toString());
            when(server.getAddresses()).thenReturn(new NovaAddresses());
            when(server.getMetadata()).thenReturn(metadata);
            metadata.put("jenkins-instance", jenkins.getRootUrl()); // Mark the slave as ours
        }

        public MockServerBuilder name(String name) {
            when(server.getName()).thenReturn(name);
            return this;
        }

        public MockServerBuilder floatingIp(String ip) {
            NovaAddress addr = mock(NovaAddress.class);
            when(addr.getType()).thenReturn("floating");
            when(addr.getAddr()).thenReturn(ip);

            server.getAddresses().add(String.valueOf(rnd.nextInt()), addr);
            return this;
        }

        public MockServerBuilder status(Server.Status status) {
            when(server.getStatus()).thenReturn(status);
            return this;
        }

        public MockServerBuilder metadataItem(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        public MockServerBuilder metadata(Map<String, String> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        public Server get() {
            return server;
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        final Statement jenkinsRuleStatement = super.apply(base, description);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                NodeProvisioner.NodeProvisionerInvoker.INITIALDELAY = NodeProvisioner.NodeProvisionerInvoker.RECURRENCEPERIOD = 1000;
                try {
                    jenkinsRuleStatement.evaluate();
                } finally {
                    for (Map.Entry<String, Proc> slave: slavesToKill.entrySet()) {
                        Proc p = slave.getValue();
                        while (p.isAlive()) {
                            System.err.println("Killing agent" + p + " for " + slave.getKey());
                            p.kill();
                        }
                    }
                }
            }
        };
    }

    @Extension
    public static class JnlpAutoConnect extends ComputerListener {
        private PluginTestRule rule;

        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            if (rule == null) return;
            System.out.println("Autolaunching agent for slave: " + c.getDisplayName());
            rule.connectJnlpSlave(c.getDisplayName());
        }

        @Override
        public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (rule == null) return;
            String current = rule.getTestDescription().toString();
            Object agent = c.getSystemProperties().get("startedBy");
            if (!current.equals(agent)) {
                throw new Error("Leaked agent connected from: " + agent);
            }
        }
    }

    public static final class MockJCloudsCloud extends JCloudsCloud {
        private static final SlaveOptions DEFAULTS = SlaveOptions.builder()
                .floatingIpPool("custom")
                .fsRoot("/tmp/jenkins")
                .slaveType(SlaveType.JNLP)
                .build()
        ;

        private final transient Openstack os = mock(Openstack.class, RETURNS_SMART_NULLS);

        public MockJCloudsCloud(JCloudsSlaveTemplate... templates) {
            this(DEFAULTS, templates);
        }

        public MockJCloudsCloud(SlaveOptions opts, JCloudsSlaveTemplate... templates) {
            super("openstack", "identity", "credential", "endPointUrl", "project", "domain", 10, 10, 10, opts, Arrays.asList(templates), true, "region", "zone");
        }

        @Override
        public @Nonnull Openstack getOpenstack() {
            return os;
        }

        @Override
        public Descriptor getDescriptor() {
            return new Descriptor();
        }

        public static final class Descriptor extends hudson.model.Descriptor<Cloud> {
            @Override
            public String getDisplayName() {
                return null;
            }
        }
    }
}
