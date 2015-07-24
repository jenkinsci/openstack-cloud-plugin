package jenkins.plugins.openstack.compute;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

public class JCloudsSlaveTemplateTest extends HudsonTestCase {
    // Following will be null if can not be validated: imageId, hardwareId, networkId, availabilityZone
    // TODO test userDataId, credentialsId
    final String TEMPLATE_PROPERTIES = "name,labelString,numExecutors,stopOnTerminate,jvmOptions,fsRoot,installPrivateKey,overrideRetentionTime,keyPairName,securityGroups,slaveType";
    final String CLOUD_PROPERTIES = "profile,identity,credential,endPointUrl,instanceCap,retentionTime,scriptTimeout,startTimeout,zone";

    public void testConfigRoundtrip() throws Exception {
        final String TEMPLATE_NAME = "test-template";
        final String CLOUD_NAME = "my-openstack";

        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(TEMPLATE_NAME, "imageId", "hardwareId",
                "openstack-slave-type1 openstack-type2", "userData", "1", false, null, null, true, 0,
                "keyPair", "network1_id,network2_id", "default", null, JCloudsCloud.SlaveType.SSH, null);

        List<JCloudsSlaveTemplate> templates = new ArrayList<JCloudsSlaveTemplate>();
        templates.add(originalTemplate);

        JCloudsCloud originalCloud = new JCloudsCloud(CLOUD_NAME, "identity", "credential", "endPointUrl", 1, 30,
                600 * 1000, 600 * 1000, null, templates);

        hudson.clouds.add(originalCloud);
        submit(createWebClient().goTo("configure").getFormByName("config"));

        final JCloudsCloud actualCloud = JCloudsCloud.getByName(CLOUD_NAME);
        assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);

        assertEqualBeans(originalTemplate, actualCloud.getTemplate(TEMPLATE_NAME), TEMPLATE_PROPERTIES);
    }
}
