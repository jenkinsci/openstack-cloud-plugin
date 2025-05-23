package jenkins.plugins.openstack.compute;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

public class JCloudsComputerTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void saveSlaveConfigPage() throws Exception {
        JCloudsSlave slave = j.provisionDummySlave("label");
        WebClient wc = j.createWebClient();
        assertThat(wc.getPage(slave).getTextContent(), not(containsString("Configure")));

        wc = j.createWebClientAllowingFailures();
        assertEquals(404, wc.getPage(slave, "configure").getWebResponse().getStatusCode());
    }

    @Test
    public void pendingDelete() throws Exception {
        JCloudsComputer computer = j.provisionDummySlave("label").getComputer();
        computer.waitUntilOnline(); // Not really needed but can affect tests negatively
        assertFalse("New slave should be online", computer.isPendingDelete());
        computer.setPendingDelete(true);
        assertTrue("Computer should be pending delete", computer.isPendingDelete());
        computer.setPendingDelete(false);
        assertFalse("Computer should not be pending delete", computer.isPendingDelete());
    }

    @Test
    public void slaveWithRetentionTimeNonZeroStillAcceptsTasksAfterFirstTaskRun() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions()
                        .getBuilder()
                        .retentionTime(10)
                        .instancesMin(1)
                        .build(),
                "label")));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        FreeStyleBuild build = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(build);
        j.waitUntilNoActivity();
        assertTrue(computer.isAcceptingTasks());
    }
}
