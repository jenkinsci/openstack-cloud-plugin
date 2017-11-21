package jenkins.plugins.openstack;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.SlaveOptions;
import jenkins.plugins.openstack.compute.UserDataConfig;
import jenkins.plugins.openstack.compute.auth.AbstractOpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
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

    public WebClient createWebClientAllowingFailures() {
        WebClient wc = createWebClient();
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    /**
     * Reusable options instance guaranteed not to collide with defaults
     */
    public static SlaveOptions dummySlaveOptions() {
        if (Jenkins.getInstance() != null) {
            dummyUserData("dummyUserDataId");
        }
        return new SlaveOptions(
                new BootSource.VolumeSnapshot("id"), "hw", "nw", "dummyUserDataId", 1, "pool", "sg", "az", 1, null, 10,
                "jvmo", "fsRoot", LauncherFactory.JNLP.JNLP, 1
        );
    }

    public static class DummyOpenstackCredential extends AbstractOpenstackCredential {
        private static final long serialVersionUID = -6458476198187349017L;

        public DummyOpenstackCredential() {
            super(CredentialsScope.SYSTEM, "my-id", "testCredential");
        }

        public DummyOpenstackCredential(String id) {
            super(CredentialsScope.SYSTEM, id, "testCredential");
        }

        @Override
        public IOSClientBuilder<? extends OSClient<?>, ?> getBuilder(String endPointUrl) {
            return null;
        }
    }

    public String dummyCredential() {
        DummyOpenstackCredential c = new DummyOpenstackCredential();
        OpenstackCredentials.add(c);
        return c.getId();
    }

    public SlaveOptions defaultSlaveOptions() {
        dummyUserData("dummyUserDataId");

        // Use some real-looking values preserving defaults to make sure plugin works with them
        return JCloudsCloud.DescriptorImpl.getDefaultOptions().getBuilder()
                .bootSource(new BootSource.Image("dummyImageId"))
                .hardwareId("dummyHardwareId")
                .networkId("dummyNetworkId")
                .userDataId("dummyUserDataId")
                .floatingIpPool("dummyPoolName")
                .availabilityZone("dummyAvailabilityZone")
                .keyPairName("dummyKeyPairName")
                .jvmOptions("dummyJvmOptions")
                .fsRoot("/tmp/jenkins")
                .launcherFactory(LauncherFactory.JNLP.JNLP)
                .build()
        ;
    }

    private static void dummyUserData(String id) {
        String userData = "SLAVE_JENKINS_HOME: ${SLAVE_JENKINS_HOME}\n" +
                "SLAVE_JVM_OPTIONS: ${SLAVE_JVM_OPTIONS}\n" +
                "JENKINS_URL: ${JENKINS_URL}\n" +
                "SLAVE_JAR_URL: ${SLAVE_JAR_URL}\n" +
                "SLAVE_JNLP_URL: ${SLAVE_JNLP_URL}\n" +
                "SLAVE_JNLP_SECRET: ${SLAVE_JNLP_SECRET}\n" +
                "SLAVE_LABELS: ${SLAVE_LABELS}\n" +
                "DO_NOT_REPLACE_THIS: ${unknown} ${VARIABLE}"
        ;
        ConfigProvider.all().get(UserDataConfig.UserDataConfigProvider.class).save(
                new Config(id, "Fake", "It is a fake", userData)
        );
    }

    public String dummySshCredential(String id) {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new BasicSSHUserPrivateKey(
                        CredentialsScope.SYSTEM, id, "john " + id, null, null, "Description " + id
                )
        );
        return id;
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
                .stderr(new DecoratingOutputStream(System.err, slaveName + " err: "))
                .stdout(new DecoratingOutputStream(System.out, slaveName + " out: "))
                .start()
        ;
        slavesToKill.put(slaveName, proc);
        return proc;
    }

    private static final class DecoratingOutputStream extends FilterOutputStream {

        private final byte[] prefix;
        private boolean newline = true;

        public DecoratingOutputStream(OutputStream out, String prefix) {
            super(out);
            this.prefix = prefix.getBytes();
        }

        @Override public void write(int b) throws IOException {
            if (newline) {
                newline = false;
                write(prefix);
            }
            super.write(b);
            if (b == '\n') {
                newline = true;
            }
        }
    }

    /**
     * Force idle slave cleanup now.
     */
    public void triggerOpenstackSlaveCleanup() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(JCloudsCleanupThread.class).execute(TaskListener.NULL);
        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();
        while (disposer.isActivated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
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
                        .name(builder.build().getName())
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
        doAnswer(new Answer<Void>() {
            @Override public Void answer(InvocationOnMock invocation) throws Throwable {
                Server server = (Server) invocation.getArguments()[0];
                running.remove(server);
                return null;
            }
        }).when(os).destroyServer(any(Server.class));
        return cloud;
    }

    public JCloudsSlave provision(JCloudsCloud cloud, String label) throws ExecutionException, InterruptedException, IOException {
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
            jenkins.addNode(slave);
            // Wait for node to be added fully - for computer to be created. This does not necessarily wait for it to be online
            for (int i = 0; i < 10; i++) {
                if (slave.toComputer() != null) return slave;
                Thread.sleep(300);
            }
            throw new AssertionError("Computer not created in time");
        } catch (Throwable ex) {
            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                cl.onFailure(plannedNode, ex);
            }
            throw ex;
        }
    }

    public JCloudsSlave provisionDummySlave(String labels) throws InterruptedException, ExecutionException, IOException {
        JCloudsCloud cloud = createCloudLaunchingDummySlaves(labels);
        return provision(cloud, labels);
    }

    public Openstack fakeOpenstackFactory() {
        return fakeOpenstackFactory(mock(Openstack.class, withSettings().defaultAnswer(RETURNS_SMART_NULLS).serializable()));
    }

    @SuppressWarnings("deprecation")
    public Openstack fakeOpenstackFactory(final Openstack os) {
        Openstack.FactoryEP.replace(new Openstack.FactoryEP() {
            @Override
            public @Nonnull Openstack getOpenstack(
                    @Nonnull String endPointUrl, @Nonnull OpenstackCredential openstackCredential, @CheckForNull String region
            ) throws FormValidation {
                return os;
            }
        });
        return os;
    }

    @SuppressWarnings("deprecation")
    public Openstack.FactoryEP mockOpenstackFactory() {
        // Yes. We are wrapping mock into a real instance on purpose. We need an not-mocked instance so the 'cache' instance
        // field is initialized properly as we are referring to it form the factory method. But, as we need to mock/verify
        // the calls to getOpenstack() calls, the implementation delegates to the mock inside to do so. The inner mock is
        // what users will configure/verify and that is what is returned from this method.
        final Openstack.FactoryEP factory = mock(Openstack.FactoryEP.class, withSettings().serializable());
        Openstack.FactoryEP.replace(new Openstack.FactoryEP() {

            @Override
            public @Nonnull Openstack getOpenstack(
                    @Nonnull String endpointUrl, @Nonnull OpenstackCredential openstackCredential, String region
            ) throws FormValidation {
                return factory.getOpenstack(endpointUrl, openstackCredential, region);
            }
        });
        return factory;
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
            server = mock(Server.class, withSettings().serializable());
            when(server.getId()).thenReturn(UUID.randomUUID().toString());
            when(server.getAddresses()).thenReturn(new NovaAddresses());
            when(server.getStatus()).thenReturn(Server.Status.ACTIVE);
            when(server.getMetadata()).thenReturn(metadata);
            when(server.getOsExtendedVolumesAttached()).thenReturn(Collections.singletonList(UUID.randomUUID().toString()));
            metadata.put("jenkins-instance", jenkins.getRootUrl()); // Mark the slave as ours
        }

        public MockServerBuilder name(String name) {
            when(server.getName()).thenReturn(name);
            return this;
        }

        public MockServerBuilder floatingIp(String ip) {
            NovaAddress addr = mock(NovaAddress.class, withSettings().serializable());
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
                            System.err.println("Killing agent " + p + " for " + slave.getKey());
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

    public static class MockJCloudsCloud extends JCloudsCloud {
        // Should not be more specific than JCloudsCloud.DescriptorImpl#DEFAULTS
        private static final SlaveOptions DEFAULTS = SlaveOptions.builder()
                .fsRoot("/tmp/jenkins")
                .launcherFactory(LauncherFactory.JNLP.JNLP)
                .build()
        ;

        private final transient Openstack os = mock(Openstack.class, withSettings().defaultAnswer(RETURNS_SMART_NULLS).serializable());

        public MockJCloudsCloud(JCloudsSlaveTemplate... templates) {
            this(DEFAULTS, templates);
        }

        public MockJCloudsCloud(SlaveOptions opts, JCloudsSlaveTemplate... templates) {
            super("openstack", "endPointUrl","zone", opts, Arrays.asList(templates), "credentialId");
        }

        @Override
        public @Nonnull Openstack getOpenstack() {
            return os;
        }

        @Override
        public Descriptor getDescriptor() {
            return new Descriptor();
        }

        @Override public @CheckForNull String slaveIsWaitingFor(@Nonnull JCloudsSlave slave) {
            if (slave.getSlaveOptions().getLauncherFactory() instanceof LauncherFactory.SSH) {
                return null; // Pretend success as we fake the connection by JNLP and waiting for IP:PORT is doomed to fail
            }
            return super.slaveIsWaitingFor(slave);
        }

        public static final class Descriptor extends hudson.model.Descriptor<Cloud> {
            @Override
            public @Nonnull String getDisplayName() {
                return "";
            }
        }
    }
}
