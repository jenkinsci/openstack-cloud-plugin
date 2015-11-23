package jenkins.plugins.openstack.compute;

import hudson.util.FormValidation;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.jclouds.ssh.SshKeys;
import org.jvnet.hudson.test.HudsonTestCase;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;

public class JCloudsCloudInsideJenkinsLiveTest extends HudsonTestCase {

    private ComputeTestFixture fixture;
    private JCloudsCloud cloud;
    private Map<String, String> generatedKeys;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        fixture = new ComputeTestFixture();
        fixture.setUp();
        generatedKeys = SshKeys.generate();

        // TODO: this may need to vary per test
        cloud = new JCloudsCloud("profile", fixture.getIdentity(), fixture.getCredential(),
                fixture.getEndpoint(), 1, DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES, 0, 600 * 1000, 600 * 1000, null,
                Collections.<JCloudsSlaveTemplate>emptyList(), true);
    }

    public void testDoTestConnectionCorrectCredentialsEtc() throws IOException {
        FormValidation result = new JCloudsCloud.DescriptorImpl().doTestConnection(fixture.getIdentity(), fixture.getCredential(),
                fixture.getEndpoint(), null);
        assertEquals("Connection succeeded!", result.getMessage());
    }

    @Override
    public void tearDown() {
        if (fixture != null)
            fixture.tearDown();
    }
}
