package jenkins.plugins.jclouds.compute;

import java.util.ArrayList;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplateTest extends HudsonTestCase {

    public void testConfigRoundtrip() throws Exception {
        String name = "testSlave";
        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(name, "imageId", null, "hardwareId", 1, 512,
                "jclouds-slave-type1 jclouds-type2", "Description", "initScript", null, "1", false, null, null, true, 0, 0, true, false, 0,
                "keyPair", true, "network1_id,network2_id", "default", null);

        List<JCloudsSlaveTemplate> templates = new ArrayList<JCloudsSlaveTemplate>();
        templates.add(originalTemplate);

        JCloudsCloud originalCloud = new JCloudsCloud("aws-profile", "identity", "credential", "endPointUrl", 1, 30,
                600 * 1000, 600 * 1000, null, templates);

        hudson.clouds.add(originalCloud);
        submit(createWebClient().goTo("configure").getFormByName("config"));

        assertEqualBeans(originalCloud, JCloudsCloud.getByName("openstack-profile"), "profile,identity,credential,privateKey,publicKey,endPointUrl");

        assertEqualBeans(originalTemplate, JCloudsCloud.getByName("aws-profile").getTemplate(name),
                "name,labelString,description,initScript,numExecutors,stopOnTerminate");

    }

}
