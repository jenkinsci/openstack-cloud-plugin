package jenkins.plugins.openstack.compute;

import java.util.Collections;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import jenkins.plugins.openstack.PluginTestRule;

import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    final String TEMPLATE_PROPERTIES = "name,labelString";
    final String CLOUD_PROPERTIES = "name,identity,credential,endPointUrl,zone";

    @Test
    public void configRoundtrip() throws Exception {
        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(
                "test-template", "openstack-slave-type1 openstack-type2", j.dummySlaveOptions()
        );

        JCloudsCloud originalCloud = new JCloudsCloud("openstack", "identity", "credential", "endPointUrl", "project",
                "domain", 1, 10, 600 * 1000, SlaveOptions.empty(), Collections.singletonList(originalTemplate), true, "region", "zone");

        j.jenkins.clouds.add(originalCloud);
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName("openstack");
        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        assertThat(actualCloud.getEffectiveSlaveOptions(), equalTo(originalCloud.getEffectiveSlaveOptions()));
        assertThat(actualCloud.getRawSlaveOptions(), equalTo(originalCloud.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualTemplate = actualCloud.getTemplate("test-template");
        j.assertEqualBeans(originalTemplate, actualTemplate, TEMPLATE_PROPERTIES);
        assertThat(actualTemplate.getEffectiveSlaveOptions(), equalTo(originalTemplate.getEffectiveSlaveOptions()));
        assertThat(actualTemplate.getRawSlaveOptions(), equalTo(originalTemplate.getRawSlaveOptions()));
    }

    @Test
    public void eraseDefaults() throws Exception {
        SlaveOptions cloudOpts = SlaveOptionsTest.CUSTOM; // Make sure nothing collides with defaults
        SlaveOptions templateOpts = cloudOpts.getBuilder().imageId("42").availabilityZone("other").build();
        assertEquals(cloudOpts.getHardwareId(), templateOpts.getHardwareId());

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "test-templateOpts", "openstack-slave-type1 openstack-type2", templateOpts
        );

        JCloudsCloud cloud = new JCloudsCloud(
                "my-openstack", "identity", "credential", "endPointUrl", "project", "domain", 10, 10, 10,
                cloudOpts,
                Collections.singletonList(template), true, "region",
                "zone");

        assertEquals(cloudOpts, cloud.getRawSlaveOptions());
        assertEquals(SlaveOptions.builder().imageId("42").availabilityZone("other").build(), template.getRawSlaveOptions());
    }
}
