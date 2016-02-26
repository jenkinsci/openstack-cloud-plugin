package jenkins.plugins.openstack.compute;

import java.util.Collections;
import jenkins.plugins.openstack.PluginTestRule;

import org.junit.Rule;
import org.junit.Test;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    // Following will be null if can not be validated: imageId, hardwareId, networkId, availabilityZone
    // TODO test userDataId, credentialsId
    final String TEMPLATE_PROPERTIES = "name,labelString,numExecutors,jvmOptions,fsRoot,overrideRetentionTime,keyPairName,securityGroups,slaveType";
    final String CLOUD_PROPERTIES = "profile,identity,credential,endPointUrl,zone,slaveOptions";

    @Test
    public void configRoundtrip() throws Exception {
        final String TEMPLATE_NAME = "test-template";
        final String CLOUD_NAME = "my-openstack";

        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(TEMPLATE_NAME, "imageId", "hardwareId",
                "openstack-slave-type1 openstack-type2", "userData", "1", null, null, 0,
                "keyPair", "network1_id,network2_id", "default", null, JCloudsCloud.SlaveType.SSH, null);

        JCloudsCloud originalCloud = new JCloudsCloud(CLOUD_NAME, "identity", "credential", "endPointUrl", null, SlaveOptions.builder().build(), Collections.singletonList(originalTemplate));

        j.jenkins.clouds.add(originalCloud);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        final JCloudsCloud actualCloud = JCloudsCloud.getByName(CLOUD_NAME);

        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        j.assertEqualBeans(originalTemplate, actualCloud.getTemplate(TEMPLATE_NAME), TEMPLATE_PROPERTIES);
    }
}
