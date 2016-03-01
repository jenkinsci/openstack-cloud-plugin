package jenkins.plugins.openstack;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.plugins.openstack.compute.CloudInstanceDefaults;
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

import javax.annotation.Nonnull;

/**
 * Test utils for plugin functional testing.
 *
 * @author ogondza
 */
public final class PluginTestRule extends JenkinsRule {

    private static final Random rnd = new Random();
    private final AtomicInteger slaveCount = new AtomicInteger(0);

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
        File jar = Which.jarFile(Channel.class);
        String url = getURL() + "computer/" + slaveName + "/slave-agent.jnlp";
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        Proc proc = new LocalLauncher(listener).launch()
                .cmds("java", "-jar", jar.getAbsolutePath(), "-jnlpUrl", url)
                .stderr(System.err)
                .stdout(System.out)
                .start()
        ;
        slavesToKill.add(proc);
        return proc;
    }

    /**
     * Force idle slave cleanup now.
     */
    public void triggerOpenstackSlaveCleanup() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(JCloudsCleanupThread.class).execute(TaskListener.NULL);
    }

    public JCloudsSlaveTemplate dummySlaveTemplate(String labels) {
        return new JCloudsSlaveTemplate(
                "template", "imageId", "hardwareId", labels, null, "42",
                "-verbose", "/tmp/slave", 42, "keyPairName", "networkId",
                "securityGroups", "", JCloudsCloud.SlaveType.JNLP, "availabilityZone"
        );
    }

    public JCloudsCloud dummyCloud(JCloudsSlaveTemplate... templates) {
        JCloudsCloud cloud = new MockJCloudsCloud(templates);
        jenkins.clouds.add(cloud);
        return cloud;
    }

    public JCloudsCloud createCloudProvisioningDummySlaves(String labels) {
        return createCloudProvisioningSlaves(dummySlaveTemplate(labels));
    }

    public JCloudsCloud createCloudProvisioningSlaves(JCloudsSlaveTemplate... templates) {
        JCloudsCloud cloud = dummyCloud(templates);
        autoconnectJnlpSlaves();
        Openstack os = cloud.getOpenstack();
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                int num = slaveCount.getAndIncrement();
                return mockServer().name("provisioned" + num).floatingIp("42.42.42." + num).get();
            }
        });
        when(os.updateInfo(any(Server.class))).thenAnswer(new Answer<Server>() {
            @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                return (Server) invocation.getArguments()[0];
            }
        });
        return cloud;
    }

    public JCloudsSlave provision(JCloudsCloud cloud, String label) throws ExecutionException, InterruptedException {
        Collection<PlannedNode> slaves = cloud.provision(Label.get(label), 1);
        return (JCloudsSlave) slaves.iterator().next().future.get();
    }

    public JCloudsSlave provisionDummySlave(String labels) throws InterruptedException, ExecutionException {
        JCloudsCloud cloud = createCloudProvisioningDummySlaves(labels);
        return provision(cloud, labels);
    }

    public MockServerBuilder mockServer() {
        return new MockServerBuilder();
    }

    public static class MockServerBuilder {

        private final Server server;

        public MockServerBuilder() {
            server = mock(Server.class);
            when(server.getAddresses()).thenReturn(new NovaAddresses());
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

        public Server get() {
            return server;
        }
    }

    private final List<Proc> slavesToKill = new ArrayList<>();

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
                    for (Proc s: slavesToKill) {
                        if (s.isAlive()) {
                            System.err.println("Killing agent" + s);
                            s.kill();
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
    }

    private static final class MockJCloudsCloud extends JCloudsCloud {
        private final transient Openstack os = mock(Openstack.class, RETURNS_SMART_NULLS);

        public MockJCloudsCloud(JCloudsSlaveTemplate... templates) {
            super("openstack", "identity", "credential", "endPointUrl", 1, CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES, 600 * 1000, null, Arrays.asList(templates), true, "public");
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
