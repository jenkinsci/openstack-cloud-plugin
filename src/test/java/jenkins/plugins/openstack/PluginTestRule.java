package jenkins.plugins.openstack;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.openstack4j.model.compute.Server;
import org.openstack4j.openstack.compute.domain.NovaAddresses;
import org.openstack4j.openstack.compute.domain.NovaAddresses.NovaAddress;

import hudson.Extension;
import hudson.Launcher.LocalLauncher;
import hudson.Proc;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Which;
import hudson.slaves.ComputerListener;
import hudson.util.StreamTaskListener;
import jenkins.plugins.openstack.compute.JCloudsCleanupThread;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.internal.Openstack;

/**
 * Test utils for plugin functional testing.
 *
 * @author ogondza
 */
public final class PluginTestRule extends JenkinsRule {

    private static final Random rnd = new Random();

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
     * Force idle slace cleanup now.
     */
    public void triggerOpenstackSlaveCleanup() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(JCloudsCleanupThread.class).execute(TaskListener.NULL);
    }

    /**
     * Add cloud to Jenkins. It is needed to use this method to have Openstack client mocked.
     */
    public JCloudsCloud addCoud(JCloudsCloud cloud) {
        cloud = spy(cloud);
        jenkins.clouds.add(cloud);
        Openstack os = mock(Openstack.class, RETURNS_SMART_NULLS);
        doReturn(os).when(cloud).getOpenstack();
        return cloud;
    }

    public JCloudsSlaveTemplate dummySlaveTemplate(String labels) {
        return new JCloudsSlaveTemplate(
                "template", "imageId", "hardwareId", labels, null, "42",
                "-verbose", "/tmp/slave", false, 42, "keyPairName", "networkId",
                "securityGroups", "", JCloudsCloud.SlaveType.JNLP, "availabilityZone"
        );
    }

    public JCloudsCloud dummyCloud(JCloudsSlaveTemplate... templates) {
        return new JCloudsCloud("openstack", "identity", "credential", "endPointUrl", 1, DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES,
                600 * 1000, 600 * 1000, null, Arrays.asList(templates), true
        );
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

        public Server get() {
            return server;
        }
    }

    private final List<Proc> slavesToKill = new ArrayList<Proc>();

    @Override
    public Statement apply(Statement base, Description description) {
        final Statement jenkinsRuleStatement = super.apply(base, description);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
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
}
