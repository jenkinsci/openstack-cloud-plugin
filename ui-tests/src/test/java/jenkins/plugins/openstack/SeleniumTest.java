/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.openstack;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.jenkinsci.test.acceptance.Matchers.*;
import static org.jenkinsci.test.acceptance.po.FormValidation.Kind.OK;
import static org.junit.Assert.assertEquals;

import jenkins.plugins.openstack.po.OpenstackBuildWrapper;
import jenkins.plugins.openstack.po.OpenstackCloud;
import jenkins.plugins.openstack.po.OpenstackOneOffSlave;
import jenkins.plugins.openstack.po.OpenstackSlaveTemplate;
import jenkins.plugins.openstack.po.UserDataConfig;

import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.TestActivation;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.config_file_provider.ConfigFileProvider;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.FormValidation;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.JenkinsConfig;
import org.jenkinsci.test.acceptance.po.MatrixBuild;
import org.jenkinsci.test.acceptance.po.MatrixProject;
import org.jenkinsci.test.acceptance.po.MatrixRun;
import org.jenkinsci.test.acceptance.po.Node;
import org.jenkinsci.test.acceptance.po.Slave;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

@WithPlugins("openstack-cloud")
public class SeleniumTest extends AbstractJUnitTest {

    private static final String CLOUD_INIT_NAME = "cloudInit";
    private static final String CLOUD_NAME = "OSCloud";
    private static final String CLOUD_DEFAULT_TEMPLATE = "ath-integration-test";
    private static final String MACHINE_USERNAME = "jenkins";
    private static final String SSH_CRED_ID = "ssh-cred-id";
    private static final int PROVISIONING_TIMEOUT = 480;
    // Must be absolute as ATM it is resolved relative to @WithCredentials
    private static final String SSH_KEY_PATH = "/jenkins/plugins/openstack/SeleniumTest/unsafe";

    public static String OS_AUTH_URL = System.getenv("OS_AUTH_URL");

    public static String OS_USERNAME = System.getenv("OS_USERNAME");
    public static String OS_USER_DOMAIN_NAME = System.getenv("OS_USER_DOMAIN_NAME");
    public static String OS_PROJECT_NAME = System.getenv("OS_PROJECT_NAME");
    public static String OS_PROJECT_DOMAIN_NAME = System.getenv("OS_PROJECT_DOMAIN_NAME");

    public static String OS_PASSWORD = System.getenv("OS_PASSWORD");
    public static String OS_HARDWARE_ID = System.getenv("OS_HARDWARE_ID");
    public static String OS_NETWORK_ID = System.getenv("OS_NETWORK_ID");
    public static String OS_IMAGE_ID = System.getenv("OS_IMAGE_ID");
    public static String OS_KEY_NAME = System.getenv("OS_KEY_NAME");
    public static String OS_FIP_POOL_NAME = System.getenv("OS_FIP_POOL_NAME");

    @BeforeClass
    public static void statiSetUp() {
        assumeThat("OS_AUTH_URL missing", OS_AUTH_URL, notNullValue());

        assumeThat("OS_USERNAME missing", OS_USERNAME, notNullValue());
        assumeThat("OS_USER_DOMAIN_NAME missing", OS_USER_DOMAIN_NAME, notNullValue());
        assumeThat("OS_PROJECT_NAME missing", OS_PROJECT_NAME, notNullValue());
        assumeThat("OS_PROJECT_DOMAIN_NAME missing", OS_PROJECT_DOMAIN_NAME, notNullValue());

        assumeThat("OS_PASSWORD missing", OS_PASSWORD, notNullValue());
        assumeThat("OS_HARDWARE_ID missing", OS_HARDWARE_ID, notNullValue());
        assumeThat("OS_NETWORK_ID missing", OS_NETWORK_ID, notNullValue());
        assumeThat("OS_IMAGE_ID missing", OS_IMAGE_ID, notNullValue());
        assumeThat("OS_KEY_NAME missing", OS_KEY_NAME, notNullValue());
    }

    @Before
    public void setUp() {
        if ("".equals(OS_USER_DOMAIN_NAME)) OS_USER_DOMAIN_NAME = null;
        if ("".equals(OS_PROJECT_DOMAIN_NAME)) OS_PROJECT_DOMAIN_NAME = null;
    }

    @After // Terminate all nodes
    public void tearDown() {
        // We have never left the config - no nodes to terminate
        if (getCurrentUrl().endsWith("/configure")) return;
        jenkins.runScript("Jenkins.instance.nodes.each { it.terminate() }");
        sleep(5000);
        String s;
        do {
            s = jenkins.runScript("os = Jenkins.instance.clouds[0]?.openstack; if (os) { os.runningNodes.each { os.destroyServer(it) }; return os.runningNodes.size() }; return 0");
        } while (!"0".equals(s));
    }

    @Test
    public void testConnection() {
        JenkinsConfig config = jenkins.getConfigPage();
        config.configure();
        OpenstackCloud cloud = addCloud(config);
        FormValidation val = cloud.testConnection();
        assertThat(val, FormValidation.reports(OK, startsWith("Connection succeeded!")));
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, SSH_KEY_PATH}, id = SSH_CRED_ID)
    public void provisionSshSlave() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    public void provisionSshSlaveWithPasswdAuth() {
        boolean isReachableFromOs = Boolean.getBoolean(System.getProperty("SeleniumTest.REACHABLE_FROM_OS"));
        assumeTrue("Skipping JNLP test as the test host is not reachable from OS. Set SeleniumTest.REACHABLE_FROM_OS in case it is.", isReachableFromOs);

        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    public void provisionSshSlaveWithPasswdAuthRetryOnFailedAuth() {
        configureCloudInit("cloud-init-authfix");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    // The test will fail when test host is not reachable from openstack machine for obvious reasons
    @Test
    // TODO: JENKINS-30784 Do not bother with credentials for jnlp slaves
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, SSH_KEY_PATH}, id = SSH_CRED_ID)
    public void provisionJnlpSlave() {
        configureCloudInit("cloud-init-jnlp");
        configureProvisioning("JNLP", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.save();
        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test @Issue("JENKINS-29998")
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, SSH_KEY_PATH}, id = SSH_CRED_ID)
    @WithPlugins("matrix-project")
    public void scheduleMatrixWithoutLabel() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");
        jenkins.configure();
        jenkins.getConfigPage().numExecutors.set(0);
        jenkins.save();

        MatrixProject job = jenkins.jobs.create(MatrixProject.class);
        job.configure();
        job.save();

        MatrixBuild pb = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().as(MatrixBuild.class);
        assertThat(pb.getNode(), equalTo((Node) jenkins));
        MatrixRun cb = pb.getConfiguration("default");
        assertThat(cb.getNode(), not(equalTo((Node) jenkins)));
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, SSH_KEY_PATH}, id = SSH_CRED_ID)
    public void usePerBuildInstance() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "unused");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        OpenstackBuildWrapper bw = job.addBuildWrapper(OpenstackBuildWrapper.class);
        bw.cloud(CLOUD_NAME);
        bw.template(CLOUD_DEFAULT_TEMPLATE);
        bw.count(1);
        // Wait a little for the other machine to start responding
        job.addShellStep("while ! ping -c 1 \"$JCLOUDS_IPS\"; do :; done");
        job.save();

        job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {MACHINE_USERNAME, SSH_KEY_PATH}, id = SSH_CRED_ID)
    public void useSingleUseSlave() {
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.addBuildWrapper(OpenstackOneOffSlave.class);
        job.save();

        Build build = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed();
        assertTrue(build.getNode().isTemporarillyOffline());
    }

    @Test
    @WithCredentials(credentialType = WithCredentials.USERNAME_PASSWORD, values = {MACHINE_USERNAME, "ath"}, id = SSH_CRED_ID)
    public void sshSlaveShouldSurviveRestart() {
        assumeTrue("This test requires a restartable Jenkins", jenkins.canRestart());
        configureCloudInit("cloud-init");
        configureProvisioning("SSH", "label");

        FreeStyleJob job = jenkins.jobs.create();
        job.configure();
        job.setLabelExpression("label");
        job.addShellStep("uname -a");
        job.save();
        Node created = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().getNode();

        jenkins.restart();

        Node reconnected = job.scheduleBuild().waitUntilFinished(PROVISIONING_TIMEOUT).shouldSucceed().getNode();

        assertEquals(created, reconnected);

        Slave slave = (Slave) reconnected;
        slave.open();
        slave.clickLink("Schedule Termination");
        waitFor(slave, pageObjectDoesNotExist(), 1000);
    }

    private OpenstackCloud addCloud(JenkinsConfig config) {
        return config.addCloud(OpenstackCloud.class)
                .profile(CLOUD_NAME)
                .endpoint(OS_AUTH_URL)
                .credential(OS_USERNAME, OS_USER_DOMAIN_NAME, OS_PROJECT_NAME, OS_PROJECT_DOMAIN_NAME, OS_PASSWORD)
        ;
    }

    private void configureCloudInit(String cloudInitName) {
        ConfigFileProvider fileProvider = new ConfigFileProvider(jenkins);
        UserDataConfig cloudInit = fileProvider.addFile(UserDataConfig.class);
        cloudInit.name(CLOUD_INIT_NAME);
        cloudInit.content(resource("SeleniumTest/" + cloudInitName).asText());
        cloudInit.save();
    }

    private void configureProvisioning(String type, String labels) {
        jenkins.configure();
        OpenstackCloud cloud = addCloud(jenkins.getConfigPage());
        if (OS_FIP_POOL_NAME != null) {
            cloud.associateFloatingIp(OS_FIP_POOL_NAME);
        }
        cloud.instanceCap(3);
        OpenstackSlaveTemplate template = cloud.addSlaveTemplate();

        template.name(CLOUD_DEFAULT_TEMPLATE);
        template.labels(labels);
        template.hardwareId(OS_HARDWARE_ID);
        template.networkId(OS_NETWORK_ID);
        template.imageId(OS_IMAGE_ID);
        template.connectionType(type);
        if ("SSH".equals(type)) {
            template.sshCredentials(SSH_CRED_ID);
        }
        template.userData(CLOUD_INIT_NAME);
        template.keyPair(OS_KEY_NAME);
        template.fsRoot("/tmp/jenkins");
        jenkins.save();
    }
}
