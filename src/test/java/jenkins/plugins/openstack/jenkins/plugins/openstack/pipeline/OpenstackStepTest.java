package jenkins.plugins.openstack.jenkins.plugins.openstack.pipeline;

import hudson.model.Result;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.*;

public class OpenstackStepTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Before
    public void setup () {
        JCloudsCloud cloud = j.createCloudLaunchingDummySlaves("whatever");
        j.jenkins.clouds.add(cloud);
    }

    @Test
    public void boot() throws Exception {

        WorkflowJob boot = j.jenkins.createProject(WorkflowJob.class, "boot");
        boot.setDefinition(new CpsFlowDefinition(
                                "def srv = openstackMachine cloud: 'openstack', template: 'template0' \n" +
                                        "echo srv.getAddress() \n" +
                                        "echo srv.id \n" +
                                        "echo srv.status \n" +
                                        "srv.destroy() \n" +
                                        "assert srv.status == null \n" +
                                        "assert srv.address == ''" , true));
        WorkflowRun b = j.assertBuildStatusSuccess(boot.scheduleBuild2(0));
        j.assertLogContains("42.42.42", b);
        j.assertLogContains("ACTIVE", b);
    }

    @Test
    public void bootUnmanaged() throws Exception {

        WorkflowJob bootUnmanaged = j.jenkins.createProject(WorkflowJob.class, "boot Unmanaged");
        bootUnmanaged.setDefinition(new CpsFlowDefinition(
                "def srv = openstackMachine cloud: 'openstack', template: 'template0', scope: 'unlimited' \n" +
                        "echo srv.address \n" +
                        "srv.destroy()" , true));
        WorkflowRun b = j.assertBuildStatusSuccess(bootUnmanaged.scheduleBuild2(0));
        j.assertLogContains("42.42.42", b);
    }

    @Test
    public void bootInvalidScope() throws Exception {

        WorkflowJob bootInvalidScope = j.jenkins.createProject(WorkflowJob.class, "bootInvalidScope");
        bootInvalidScope.setDefinition(new CpsFlowDefinition(
                        " def srv = openstackMachine cloud: 'openstack', template: 'template0', scope: 'invalidScope' \n" +
                        " echo 'shouldnt reach' " , true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, bootInvalidScope.scheduleBuild2(0));
        j.assertLogNotContains("shouldnt reach", b);
        j.assertLogContains("Invalid scope", b);
    }

    @Test
    public void bootInvalidTemplate() throws Exception {

        WorkflowJob bootInvalidTEmplate = j.jenkins.createProject(WorkflowJob.class, "bootInvalidTEmplate");
        bootInvalidTEmplate.setDefinition(new CpsFlowDefinition(
                " def srv = openstackMachine cloud: 'openstack', template: 'OtherTemplate'\n" +
                        " echo 'shouldnt reach' " , true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, bootInvalidTEmplate.scheduleBuild2(0));
        j.assertLogNotContains("shouldnt reach", b);
        j.assertLogContains("Invalid template", b);
    }

    @Test
    public void checkSerializableSimplifiedServer() throws Exception {

        WorkflowJob bootSerializableCheck = j.jenkins.createProject(WorkflowJob.class, "bootSerializableCheck");
        bootSerializableCheck.setDefinition(new CpsFlowDefinition(
                " def srv = openstackMachine cloud: 'openstack', template: 'template0', scope: 'time:1970-01-01 00:00:00' \n" +
                        "node ('master') { \n" +
                        "  sh \"echo Instance IP: ${srv.address}\" \n" +
                        "} \n" +
                        "srv.destroy()", true));
        WorkflowRun b = j.assertBuildStatusSuccess(bootSerializableCheck.scheduleBuild2(0));
        j.assertLogContains("Instance IP: 42.42.42", b);
    }
}
