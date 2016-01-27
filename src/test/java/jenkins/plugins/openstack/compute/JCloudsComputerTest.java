package jenkins.plugins.openstack.compute;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

import jenkins.plugins.openstack.PluginTestRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

public class JCloudsComputerTest {
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void saveSlaveConfigPage() throws Exception {
        JCloudsSlave slave = j.provisionDummySlave("label");
        WebClient wc = j.createWebClient();
        assertThat(wc.getPage(slave).getTextContent(), not(containsString("Configure")));

        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.setPrintContentOnFailingStatusCode(false);
        assertEquals(404, wc.getPage(slave, "configure").getWebResponse().getStatusCode());
    }
}
