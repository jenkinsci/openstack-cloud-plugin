package jenkins.plugins.openstack.compute;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import jenkins.plugins.openstack.PluginTestRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        JCloudsComputer computer = (JCloudsComputer) j.provisionDummySlave("label").toComputer();
        assertFalse("New slave should be online", computer.isPendingDelete());
        boolean old = computer.setPendingDelete(true);
        assertFalse("Computer should be pending delete", old);
        assertTrue("Computer should be pending delete", computer.isPendingDelete());
        old = computer.setPendingDelete(false);
        assertTrue("Computer should be pending delete", old);
        assertFalse("Computer should not be pending delete", computer.isPendingDelete());
    }
}
