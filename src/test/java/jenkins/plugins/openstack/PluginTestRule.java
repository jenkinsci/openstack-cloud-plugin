package jenkins.plugins.openstack;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import hudson.Extension;
import hudson.Launcher.LocalLauncher;
import hudson.Proc;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Channel;
import hudson.remoting.Which;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner.NodeProvisionerInvoker;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsCleanupThread;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsPreCreationThread;
import jenkins.plugins.openstack.compute.JCloudsSlave;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.SlaveOptions;
import jenkins.plugins.openstack.compute.UserDataConfig;
import jenkins.plugins.openstack.compute.auth.AbstractOpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import jenkins.plugins.openstack.nodeproperties.NodePropertyOne;
import jenkins.plugins.openstack.nodeproperties.NodePropertyThree;
import jenkins.plugins.openstack.nodeproperties.NodePropertyTwo;

import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.stubbing.Answer;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.compute.domain.NovaAddresses;
import org.openstack4j.openstack.compute.domain.NovaAddresses.NovaAddress;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
        if (Jenkins.getInstanceOrNull() != null) {
            dummyUserData("dummyUserDataId");
        }
        return new SlaveOptions(
                new BootSource.VolumeSnapshot("id"), "hw", "nw1,mw2", "dummyUserDataId", 1, 2, "pool", "sg", "az", 1, null, 10,
                "jvmo", "fsRoot", LauncherFactory.JNLP.JNLP, mkListOfNodeProperties(1, 2), 1
        );
    }

    public static List<NodeProperty<Node>> mkListOfNodeProperties(int... npTypes) {
        final Builder<NodeProperty<Node>> b = ImmutableList.builder();
        for (int number : npTypes) {
            b.add(mkNodeProperty(number));
        }
        return b.build();
    }

    public static NodeProperty<Node> mkNodeProperty(int number) {
        switch (number) {
        case 1:
            return new NodePropertyOne();
        case 2:
            return new NodePropertyTwo();
        case 3:
            return new NodePropertyThree();
        default:
            throw new IllegalArgumentException("Need 1, 2 or 3: Got " + number);
        }
    }

    public static class DummyOpenstackCredentials extends AbstractOpenstackCredential {
        private static final long serialVersionUID = -6458476198187349017L;

        private DummyOpenstackCredentials() {
            super(CredentialsScope.SYSTEM, "my-id", "testCredentials");
        }

        @Override
        public @Nonnull IOSClientBuilder<? extends OSClient<?>, ?> getBuilder(String endPointUrl) {
            throw new AuthenticationException(getClass().getSimpleName() + " can not be use to create client", -1);
        }
    }

    public String dummyCredentials() {
        DummyOpenstackCredentials c = new DummyOpenstackCredentials();
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
        GlobalConfigFiles.get().save(
                new UserDataConfig(id, "Fake", "It is a fake", userData)
        );
    }

    public String dummySshCredentials(String id) {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new BasicSSHUserPrivateKey(
                        CredentialsScope.SYSTEM, id, "john " + id, null, null, "Description " + id
                )
        );
        return id;
    }

    public static BasicSSHUserPrivateKey extractSshCredentials(LauncherFactory lf) {
        assertThat(lf, Matchers.instanceOf(LauncherFactory.SSH.class));
        LauncherFactory.SSH sshlf = (LauncherFactory.SSH) lf;
        return (BasicSSHUserPrivateKey) SSHLauncher.lookupSystemCredentials(sshlf.getCredentialsId());
    }

    public void autoconnectJnlpSlaves() {
        jenkins.getExtensionList(ComputerListener.class).get(JnlpAutoConnect.class).rule = this;
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

    /**
     * Force slave pre-creation now.
     */
    public void triggerSlavePreCreation() {
        JCloudsPreCreationThread.all().get(JCloudsPreCreationThread.class).execute(TaskListener.NULL);
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

    public JCloudsCloud configureSlaveLaunchingWithFloatingIP(String labels) {
        return configureSlaveLaunchingWithFloatingIP(dummyCloud(dummySlaveTemplate(labels)));
    }

    public JCloudsCloud configureSlaveLaunchingWithFloatingIP(JCloudsCloud cloud) {
        autoconnectJnlpSlaves();
        return configureSlaveProvisioningWithFloatingIP(cloud);
    }

    // Addresses with 42 as floating while those with 43 are fixed
    public enum NetworkAddress {
        FLOATING_4 {
            @Override public void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger cnt) {
                serverBuilder.withFloatingIpv4("42.42.42." + cnt.incrementAndGet());
            }
        },
        FLOATING_6 {
            @Override public void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger cnt) {
                serverBuilder.withFloatingIpv6("4242::" + cnt.incrementAndGet());
            }
        },
        FIXED_4 {
            @Override public void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger cnt) {
                serverBuilder.withFixedIPv4("43.43.43." + cnt.incrementAndGet());
            }
        },
        FIXED_6 {
            @Override public void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger cnt) {
                serverBuilder.withFixedIPv6("4343::" + cnt.incrementAndGet());
            }
        },
        FIXED_4_NO_EXPLICIT_TYPE {
            @Override public void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger cnt) {
                serverBuilder.withFixedIPv4WithoutExplicitType("43.43.43." + cnt.incrementAndGet());
            }
        };

        public abstract void apply(@Nonnull MockServerBuilder serverBuilder, @Nonnull AtomicInteger sequence);
    }

    /**
     * The provisioning future will never complete as it will wait for launch.
     */
    public JCloudsCloud configureSlaveProvisioningWithFloatingIP(final JCloudsCloud cloud) {
        return configureSlaveProvisioning(cloud, Collections.singletonList(NetworkAddress.FLOATING_4));
    }

    public JCloudsCloud configureSlaveProvisioning(JCloudsCloud cloud, Collection<NetworkAddress> networks) {
        if (cloud.getTemplates().size() == 0) throw new Error("Unable to provision - no templates provided");

        final List<Server> running = new ArrayList<>();
        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenAnswer((Answer<Server>) invocation -> {
            ServerCreateBuilder builder = (ServerCreateBuilder) invocation.getArguments()[0];

            ServerCreate create = builder.build();
            MockServerBuilder serverBuilder = mockServer().name(create.getName()).metadata(create.getMetaData());
            for (NetworkAddress network : networks) {
                network.apply(serverBuilder, slaveCount);
            }
            Server machine = serverBuilder.get();

            synchronized (running) {
                running.add(machine);
            }
            return machine;
        });
        when(os.updateInfo(any(Server.class))).thenAnswer((Answer<Server>) invocation1 -> (Server) invocation1.getArguments()[0]);
        when(os.getRunningNodes()).thenAnswer((Answer<List<Server>>) invocation1 -> {
            synchronized (running) {
                return new ArrayList<>(running);
            }
        });
        when(os.getServerById(any(String.class))).thenAnswer((Answer<Server>) invocation1 -> {
            String expected = (String) invocation1.getArguments()[0];
            synchronized (running) {
                for (Server s: running) {
                    if (expected.equals(s.getId())) {
                        return s;
                    }
                }
            }

            throw new NoSuchElementException("Does not exist");
        });
        doAnswer((Answer<Void>) invocation1 -> {
            Server server1 = (Server) invocation1.getArguments()[0];
            synchronized (running) {
                running.remove(server1);
            }
            return null;
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
            // Unwrap ExecutionException as NodeProvisioner does it too
            Throwable problem = (ex instanceof ExecutionException) ? ex.getCause(): ex;
            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                cl.onFailure(plannedNode, problem);
            }
            throw ex;
        }
    }

    public JCloudsSlave provisionDummySlave(String labels) throws InterruptedException, ExecutionException, IOException {
        JCloudsCloud cloud = configureSlaveLaunchingWithFloatingIP(labels);
        return provision(cloud, labels);
    }

    public Openstack fakeOpenstackFactory() {
        return fakeOpenstackFactory(mock(Openstack.class, withSettings().defaultAnswer(RETURNS_SMART_NULLS).serializable()));
    }

    public Openstack fakeOpenstackFactory(final Openstack os) {
        Openstack.FactoryEP.replace(new Openstack.FactoryEP() {
            @Override
            public @Nonnull Openstack getOpenstack(
                    @Nonnull String endPointUrl, boolean ignoreSsl, @Nonnull OpenstackCredential openstackCredential, @CheckForNull String region
            ) {
                return os;
            }
        });
        return os;
    }

    public Openstack.FactoryEP mockOpenstackFactory() {
        // Yes. We are wrapping mock into a real instance on purpose. We need an not-mocked instance so the 'cache' instance
        // field is initialized properly as we are referring to it form the factory method. But, as we need to mock/verify
        // the calls to getOpenstack() calls, the implementation delegates to the mock inside to do so. The inner mock is
        // what users will configure/verify and that is what is returned from this method.
        final Openstack.FactoryEP factory = mock(Openstack.FactoryEP.class, withSettings().serializable());
        Openstack.FactoryEP.replace(new Openstack.FactoryEP() {

            @Override
            public @Nonnull Openstack getOpenstack(
                    @Nonnull String endpointUrl, boolean ignoreSsl, @Nonnull OpenstackCredential openstackCredential, String region
            ) throws FormValidation {
                return factory.getOpenstack(endpointUrl, ignoreSsl, openstackCredential, region);
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

        public MockServerBuilder withFloatingIpv4(String ip) {
            return withAddress(ip, 4, "floating");
        }

        public MockServerBuilder withFloatingIpv6(String ip) {
            return withAddress(ip, 6, "floating");
        }

        public MockServerBuilder withFixedIPv4(String ip) {
            return withAddress(ip, 4, "fixed");
        }

        public MockServerBuilder withFixedIPv6(String ip) {
            return withAddress(ip, 6, "fixed");
        }

        public MockServerBuilder withFixedIPv4WithoutExplicitType(String ip) {
            return withAddress(ip, 4, null);
        }

        public MockServerBuilder withAddress(String ip, int i, String fixed) {
            NovaAddress addr = mock(NovaAddress.class, withSettings().serializable());
            when(addr.getVersion()).thenReturn(i);
            when(addr.getAddr()).thenReturn(ip);
            when(addr.getType()).thenReturn(fixed);

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
                NodeProvisionerInvoker.INITIALDELAY = NodeProvisionerInvoker.RECURRENCEPERIOD = LoadStatistics.CLOCK = 1000;
                try {
                    jenkinsRuleStatement.evaluate();
                } finally {
                    for (Map.Entry<String, Proc> slave: slavesToKill.entrySet()) {
                        killJnlpAgentProcess(slave.getKey(), slave.getValue());
                    }
                }
            }
        };
    }

    private void killJnlpAgentProcess(String name, Proc p) throws IOException, InterruptedException {
        while (p.isAlive()) {
            System.err.println("Killing agent " + p + " for " + name);
            p.kill();
        }
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
            super("openstack", "endPointUrl", false,"zone", opts, Arrays.asList(templates), "credentialsId");
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

    public TypeSafeMatcher<FormValidation> validateAs(final FormValidation.Kind kind, final String msg) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText(kind.toString() + ": " + msg);
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, org.hamcrest.Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return kind.equals(item.kind) && Objects.equals(item.getMessage(), msg);
            }
        };
    }

    public TypeSafeMatcher<FormValidation> validateAs(final FormValidation expected) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText(expected.kind.toString() + ": " + expected.getMessage());
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, org.hamcrest.Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return expected.kind.equals(item.kind) && Objects.equals(item.getMessage(), expected.getMessage());
            }
        };
    }
}
