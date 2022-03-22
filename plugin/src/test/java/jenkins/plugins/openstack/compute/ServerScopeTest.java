package jenkins.plugins.openstack.compute;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.OneShotEvent;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import jenkins.util.Timer;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.WithoutJenkins;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.startsWith;
import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerScopeTest {
    @Rule public PluginTestRule j = new PluginTestRule();

    private static final Server mockServer = mock(Server.class);
    static {
        // Old enough so node scope consider it applicable
        when(mockServer.getCreated()).thenReturn(new Date(System.currentTimeMillis() - 1000 * 60 * 61));
    }

    @Test @WithoutJenkins
    public void parse() {
        ServerScope.Node node = (ServerScope.Node) ServerScope.parse("node:asdf");
        assertEquals("asdf", node.getName());
        assertEquals("node:asdf", node.getValue());
        assertEquals(node, ServerScope.parse(node.getValue()));

        ServerScope.Node node2 = (ServerScope.Node) ServerScope.parse("node:asdf:-4242");
        assertEquals("asdf", node2.getName());
        assertEquals("node:asdf:-4242", node2.getValue());
        assertEquals(node2, ServerScope.parse(node2.getValue()));
        assertNotEquals(node, node2);

        ServerScope.Build run = (ServerScope.Build) ServerScope.parse("run:asdf:4");
        assertEquals("asdf", run.getProject());
        assertEquals(4, run.getRunNumber());
        assertEquals("run:asdf:4", run.getValue());
        assertEquals(run, ServerScope.parse(run.getValue()));

        ServerScope.Time time = (ServerScope.Time) ServerScope.parse("time:2017-01-11 14:09:25");
        ServerScope.Time time2 = (ServerScope.Time) ServerScope.parse(time.getValue());
        assertEquals("time:2017-01-11 14:09:25", time.getValue());
        assertEquals(time, time2);

        ServerScope.Unlimited unlimited = (ServerScope.Unlimited) ServerScope.parse("unlimited:Custom reason specified here if needed");
        assertEquals("unlimited:unlimited", unlimited.getValue());
        assertEquals(unlimited, ServerScope.parse(unlimited.getValue()));
    }

    @Test
    public void nodeScope() throws Exception {
        final Id id = new Id("foo", "bar", "baz");
        final JCloudsSlave js = new JCloudsSlave(id, j.mockServer().withFixedIPv4("1.1.1.1").name("foo").get(), "foo", j.defaultSlaveOptions());
        Server mock = mock(Server.class);
        when(mock.getMetadata()).thenReturn(Collections.singletonMap(
                ServerScope.METADATA_KEY,
                new ServerScope.Node(id.getNodeName(), id).getValue())
        );

        assertTrue(new ServerScope.Node(js.getNodeName() + "nonono").isOutOfScope(mock));
        assertTrue(new ServerScope.Node(js.getNodeName(), new Id("foo", "bar", "baz")).isOutOfScope(mock));
        assertFalse(new ServerScope.Node(js.getNodeName()).isOutOfScope(mock));
        assertFalse(new ServerScope.Node(js.getNodeName(), id).isOutOfScope(mock));
    }

    @Test
    public void avoidRunningOutOfScopeDuringProvisioning() throws Exception {
        OneShotEvent provisioning = new OneShotEvent();

        LauncherFactory lf = new BlockingCommandLauncherFactory(provisioning);
        SlaveOptions slaveOptions = j.defaultSlaveOptions().getBuilder().launcherFactory(lf).build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(slaveOptions, "label")));
        ScheduledFuture<JCloudsSlave> provisionFuture = Timer.get().schedule(() -> j.provision(cloud, "label"), 0, TimeUnit.SECONDS);

        final Openstack os = cloud.getOpenstack();
        while (os.getRunningNodes().size() == 0) {
            Thread.sleep(1000);
        }

        j.triggerOpenstackSlaveCleanup();

        provisioning.signal();

        provisionFuture.get();

        assertThat(JCloudsComputer.getAll(), iterableWithSize(1));
        assertThat(os.getRunningNodes(), iterableWithSize(1));
    }

    @Test
    public void runScope() throws Exception {
        FreeStyleProject asdf = j.createFreeStyleProject("asdf");
        asdf.getBuildersList().add(new SleepBuilder(1000000));
        FreeStyleBuild build = asdf.scheduleBuild2(0).waitForStart();

        ServerScope.Build alive = new ServerScope.Build(build);
        assertFalse(alive.isOutOfScope(mockServer));
        assertEquals("run:asdf:1", alive.getValue());

        ServerScope.Build rotated = new ServerScope.Build("asdf:42");
        assertTrue(rotated.isOutOfScope(mockServer));
        assertEquals("run:asdf:42", rotated.getValue());

        ServerScope.Build jobGone = new ServerScope.Build("nonono:1");
        assertTrue(jobGone.isOutOfScope(mockServer));
        assertEquals("run:nonono:1", jobGone.getValue());
    }

    @Test @WithoutJenkins
    public void timeScope() throws Exception {
        ServerScope.Time alive = new ServerScope.Time(1, TimeUnit.DAYS);
        assertFalse(alive.isOutOfScope(mockServer));
        assertThat(alive.getValue(), startsWith("time:20"));

        ServerScope.Time timedOut = new ServerScope.Time(0, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertTrue(timedOut.isOutOfScope(mockServer));
        assertThat(timedOut.getValue(), startsWith("time:20"));
    }

    @Test @WithoutJenkins
    public void unlimitedScope() {
        ServerScope.Unlimited alive = ServerScope.Unlimited.getInstance();
        assertFalse(alive.isOutOfScope(mockServer));
        assertThat(alive.getValue(), equalTo("unlimited:unlimited"));
    }

    private static class BlockingCommandLauncherFactory extends TestCommandLauncherFactory {
        private static final long serialVersionUID = 3119775368712934923L;
        private final transient OneShotEvent provisioning;

        private BlockingCommandLauncherFactory(OneShotEvent provisioning) {
            this.provisioning = provisioning;
        }

        @Override
        public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) throws JCloudsCloud.ProvisioningFailedException {
            return provisioning.isSignaled() ? null: "blocked";
        }
    }
}
