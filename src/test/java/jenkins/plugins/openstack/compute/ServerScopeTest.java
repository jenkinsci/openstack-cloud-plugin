package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.WithoutJenkins;

import java.util.concurrent.TimeUnit;

/**
 * @author ogondza.
 */
public class ServerScopeTest {
    @Rule public JenkinsRule j = new JenkinsRule();

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

        ServerScope.Time time = (ServerScope.Time) ServerScope.parse("time:2017-01-11T14:09:25.687+0100");
        ServerScope.Time time2 = (ServerScope.Time) ServerScope.parse(time.getValue());
        assertEquals("time:2017-01-11T14:09:25.687+0100", time.getValue());
        assertEquals(time, time2);
    }

    @Test
    public void nodeScope() throws Exception {
        DumbSlave slave = j.createSlave();
        ServerScope.Node alive = new ServerScope.Node(slave.getNodeName());
        ServerScope.Node dead = new ServerScope.Node(slave.getNodeName() + "nonono");

        assertFalse(alive.isOutOfScope());
        assertTrue(dead.isOutOfScope());
    }

    @Test
    public void runScope() throws Exception {
        FreeStyleProject asdf = j.createFreeStyleProject("asdf");
        asdf.getBuildersList().add(new SleepBuilder(1000000));
        FreeStyleBuild build = asdf.scheduleBuild2(0).waitForStart();

        ServerScope.Build alive = new ServerScope.Build(build);
        assertFalse(alive.isOutOfScope());
        assertEquals("run:asdf:1", alive.getValue());

        ServerScope.Build rotated = new ServerScope.Build("asdf:42");
        assertTrue(rotated.isOutOfScope());
        assertEquals("run:asdf:42", rotated.getValue());

        ServerScope.Build jobGone = new ServerScope.Build("nonono:1");
        assertTrue(jobGone.isOutOfScope());
        assertEquals("run:nonono:1", jobGone.getValue());
    }

    @Test
    public void timeScope() throws Exception {
        ServerScope.Time alive = new ServerScope.Time(1, TimeUnit.DAYS);
        assertFalse(alive.isOutOfScope());
        assertThat(alive.getValue(), startsWith("time:20"));

        ServerScope.Time timedOut = new ServerScope.Time(0, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertTrue(timedOut.isOutOfScope());
        assertThat(timedOut.getValue(), startsWith("time:20"));
    }
}
