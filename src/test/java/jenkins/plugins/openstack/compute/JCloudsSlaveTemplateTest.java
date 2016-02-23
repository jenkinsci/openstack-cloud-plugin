package jenkins.plugins.openstack.compute;

import java.util.ArrayList;
import java.util.List;
import jenkins.plugins.openstack.PluginTestRule;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;
import org.junit.Rule;
import org.junit.Test;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    // Following will be null if can not be validated: imageId, hardwareId, networkId, availabilityZone
    // TODO test userDataId, credentialsId
    final String TEMPLATE_PROPERTIES = "name,labelString,numExecutors,jvmOptions,fsRoot,overrideRetentionTime,keyPairName,securityGroups,slaveType";
    final String CLOUD_PROPERTIES = "profile,identity,credential,endPointUrl,instanceCap,retentionTime,startTimeout,zone";

    @Test
    public void configRoundtrip() throws Exception {
        final String TEMPLATE_NAME = "test-template";
        final String CLOUD_NAME = "my-openstack";

        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(TEMPLATE_NAME, "imageId", "hardwareId",
                "openstack-slave-type1 openstack-type2", "userData", "1", null, null, 0,
                "keyPair", "network1_id,network2_id", "default", null, JCloudsCloud.SlaveType.SSH, null);

        List<JCloudsSlaveTemplate> templates = new ArrayList<>();
        templates.add(originalTemplate);

        JCloudsCloud originalCloud = new JCloudsCloud(CLOUD_NAME, "identity", "credential", "endPointUrl", 1, DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES,
                600 * 1000, null, templates, true);

        j.jenkins.clouds.add(originalCloud);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        final JCloudsCloud actualCloud = JCloudsCloud.getByName(CLOUD_NAME);

        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        j.assertEqualBeans(originalTemplate, actualCloud.getTemplate(TEMPLATE_NAME), TEMPLATE_PROPERTIES);
    }
}
