package jenkins.plugins.openstack.compute;

import java.util.Collections;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import jenkins.plugins.openstack.PluginTestRule;

import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    final String TEMPLATE_PROPERTIES = "name,labelString";
    final String CLOUD_PROPERTIES = "profile,identity,credential,endPointUrl,zone";

    @Test
    public void configRoundtrip() throws Exception {
        final String TEMPLATE_NAME = "test-template";
        final String CLOUD_NAME = "my-openstack";

        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(
                TEMPLATE_NAME, "openstack-slave-type1 openstack-type2", j.dummySlaveOptions()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(
                CLOUD_NAME, "identity", "credential", "endPointUrl", "zone",
                SlaveOptions.empty(),
                Collections.singletonList(originalTemplate)
        );

        j.jenkins.clouds.add(originalCloud);
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName(CLOUD_NAME);
        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        assertThat(actualCloud.getEffectiveSlaveOptions(), equalTo(originalCloud.getEffectiveSlaveOptions()));
        assertThat(actualCloud.getRawSlaveOptions(), equalTo(originalCloud.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualTemplate = actualCloud.getTemplate(TEMPLATE_NAME);
        j.assertEqualBeans(originalTemplate, actualTemplate, TEMPLATE_PROPERTIES);
        assertThat(actualTemplate.getEffectiveSlaveOptions(), equalTo(originalTemplate.getEffectiveSlaveOptions()));
        assertThat(actualTemplate.getRawSlaveOptions(), equalTo(originalTemplate.getRawSlaveOptions()));
    }
}
