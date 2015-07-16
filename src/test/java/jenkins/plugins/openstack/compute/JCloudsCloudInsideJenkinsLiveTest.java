package jenkins.plugins.openstack.compute;

import static org.junit.Assert.assertEquals;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.jclouds.ssh.SshKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class JCloudsCloudInsideJenkinsLiveTest {

    public @Rule JenkinsRule j = new JenkinsRule();

    private ComputeTestFixture fixture;
    private JCloudsCloud cloud;
    private Map<String, String> generatedKeys;

    @Before
    public void setUp() {
        fixture = new ComputeTestFixture();
        fixture.setUp();
        generatedKeys = SshKeys.generate();

        // TODO: this may need to vary per test
        cloud = new JCloudsCloud("profile", fixture.getIdentity(), fixture.getCredential(),
                fixture.getEndpoint(), 1, 30, 600 * 1000, 600 * 1000, null,
                Collections.<JCloudsSlaveTemplate>emptyList());
    }

    @Test
    public void testDoTestConnectionCorrectCredentialsEtc() throws IOException {
        FormValidation result = new JCloudsCloud.DescriptorImpl().doTestConnection(fixture.getIdentity(), fixture.getCredential(),
                fixture.getEndpoint(), null);
        assertEquals("Connection succeeded!", result.getMessage());
    }

    @After
    public void tearDown() {
        if (fixture != null)
            fixture.tearDown();
    }
}
