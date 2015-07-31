package jenkins.plugins.openstack.compute;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplateTest extends HudsonTestCase {

    public void testConfigRoundtrip() throws Exception {
        String name = "testSlave";
        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(name, "imageId", "hardwareId",
                "openstack-slave-type1 openstack-type2", "userData", "1", false, null, null, true, 0,
                "keyPair", "network1_id,network2_id", "floatingIPPool1_name", "default", null, JCloudsCloud.SlaveType.SSH, null);

        List<JCloudsSlaveTemplate> templates = new ArrayList<JCloudsSlaveTemplate>();
        templates.add(originalTemplate);

        JCloudsCloud originalCloud = new JCloudsCloud("aws-profile", "identity", "credential", "endPointUrl", 1, DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES,
                0, 600 * 1000, 600 * 1000, null, templates, true);

        hudson.clouds.add(originalCloud);
        submit(createWebClient().goTo("configure").getFormByName("config"));

        assertEqualBeans(originalCloud, JCloudsCloud.getByName("openstack-profile"), "profile,identity,credential,privateKey,publicKey,endPointUrl");

        assertEqualBeans(originalTemplate, JCloudsCloud.getByName("aws-profile").getTemplate(name),
                "name,labelString,description,numExecutors,stopOnTerminate");

    }

}
