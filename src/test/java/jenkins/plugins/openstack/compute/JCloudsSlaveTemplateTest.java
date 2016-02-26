package jenkins.plugins.openstack.compute;

import java.util.Collections;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import jenkins.plugins.openstack.PluginTestRule;

import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    // Following will be null if can not be validated: imageId, hardwareId, networkId, availabilityZone
    // TODO test userDataId, credentialsId
    final String TEMPLATE_PROPERTIES = "name,labelString,slaveOptions";
    final String CLOUD_PROPERTIES = "profile,identity,credential,endPointUrl,zone,slaveOptions";

    @Test
    public void configRoundtrip() throws Exception {
        final String TEMPLATE_NAME = "test-template";
        final String CLOUD_NAME = "my-openstack";

        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(
                TEMPLATE_NAME,
                "openstack-slave-type1 openstack-type2",
                SlaveOptions.builder()
                        .imageId("myImageId")
                        .hardwareId("myHardwareId")
                        .numExecutors(1)
                        .keyPairName("MyKeyPair")
                        .networkId("network1_id,network2_id")
                        .securityGroups("MySecurityGroup")
                        .slaveType(JCloudsCloud.SlaveType.SSH)
                        .build()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(CLOUD_NAME, "identity", "credential", "endPointUrl", null, SlaveOptions.builder().build(), Collections.singletonList(originalTemplate));

        j.jenkins.clouds.add(originalCloud);
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        String formText = form.getPage().getWebResponse().getContentAsString();
        assertThat(formText, containsString("myImageId"));
        assertThat(formText, containsString("myHardwareId"));
        assertThat(formText, containsString("penstack-slave-type1 openstack-type2"));
        assertThat(formText, containsString("MyKeyPair"));
        assertThat(formText, containsString("network1_id,network2_id"));
        assertThat(formText, containsString("MySecurityGroup"));
        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName(CLOUD_NAME);

        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);

        JCloudsSlaveTemplate actualCloudTemplate = actualCloud.getTemplate(TEMPLATE_NAME);
        assertNotNull(actualCloudTemplate);
        j.assertEqualBeans(originalTemplate, actualCloudTemplate, TEMPLATE_PROPERTIES);
    }
}
