package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.WithoutJenkins;
import org.openstack4j.model.compute.Server;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * @author ogondza.
 */
public class ServerScopeTest {
    @Rule public JenkinsRule j = new JenkinsRule();

    private static final Server mockServer = mock(Server.class);
    {
        // Old enough so node scope consider it applicable
        when(mockServer.getCreated()).thenReturn(new Date(System.currentTimeMillis() - 1000 * 60 * 61));
    }

    @Test @WithoutJenkins
    public void parse() throws Exception {
        ServerScope.Node node = (ServerScope.Node) ServerScope.parse("node:asdf");
        assertEquals("asdf", node.getName());
        assertEquals("node:asdf", node.getValue());
        assertEquals(node, ServerScope.parse(node.getValue()));

        ServerScope.Build run = (ServerScope.Build) ServerScope.parse("run:asdf:4");
        assertEquals("asdf", run.getProject());
        assertEquals(4, run.getRunNumber());
        assertEquals("run:asdf:4", run.getValue());
        assertEquals(run, ServerScope.parse(run.getValue()));

        ServerScope.Time time = (ServerScope.Time) ServerScope.parse("time:2017-01-11 14:09:25");
        ServerScope.Time time2 = (ServerScope.Time) ServerScope.parse(time.getValue());
        assertEquals("time:2017-01-11 14:09:25", time.getValue());
        assertEquals(time, time2);
    }

    @Test
    public void nodeScope() throws Exception {
        DumbSlave slave = j.createSlave();
        ServerScope.Node alive = new ServerScope.Node(slave.getNodeName());
        ServerScope.Node dead = new ServerScope.Node(slave.getNodeName() + "nonono");

        assertFalse(alive.isOutOfScope(mockServer));
        assertTrue(dead.isOutOfScope(mockServer));
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

    @Test
    public void timeScope() throws Exception {
        ServerScope.Time alive = new ServerScope.Time(1, TimeUnit.DAYS);
        assertFalse(alive.isOutOfScope(mockServer));
        assertThat(alive.getValue(), startsWith("time:20"));

        ServerScope.Time timedOut = new ServerScope.Time(0, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertTrue(timedOut.isOutOfScope(mockServer));
        assertThat(timedOut.getValue(), startsWith("time:20"));
    }
}
