package jenkins.plugins.openstack.pipeline;

import hudson.model.Result;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.ServerScope;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.*;
import org.openstack4j.model.compute.Server;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.instanceOf;

public class OpenstackStepTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();
    private JCloudsCloud cloud;
    private Openstack openstack;

    @Before
    public void setup () {
        cloud = j.createCloudLaunchingDummySlaves("whatever");
        j.jenkins.clouds.add(cloud);
        openstack = cloud.getOpenstack();
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
                                        "assert srv.address == null" , true));
        WorkflowRun b = j.assertBuildStatusSuccess(boot.scheduleBuild2(0));
        j.assertLogContains("42.42.42", b);
        j.assertLogContains("ACTIVE", b);

        assertThat(openstack.getRunningNodes(), emptyIterable());
    }

    @Test
    public void bootUnmanaged() throws Exception {

        WorkflowJob bootUnmanaged = j.jenkins.createProject(WorkflowJob.class, "boot Unmanaged");
        bootUnmanaged.setDefinition(new CpsFlowDefinition(
                "def srv = openstackMachine cloud: 'openstack', template: 'template0', scope: 'unlimited' \n" +
                        "echo srv.address \n" , true));
        WorkflowRun b = j.assertBuildStatusSuccess(bootUnmanaged.scheduleBuild2(0));
        j.assertLogContains("42.42.42", b);

        List<Server> nodes = openstack.getRunningNodes();
        assertThat(nodes, Matchers.<Server>iterableWithSize(1));
        assertThat(ServerScope.extract(nodes.get(0)), instanceOf(ServerScope.Unlimited.class));
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

        assertThat(openstack.getRunningNodes(), emptyIterable());
    }

    @Test
    public void bootInvalidTemplate() throws Exception {

        WorkflowJob bootInvalidTemplate = j.jenkins.createProject(WorkflowJob.class, "bootInvalidTemplate");
        bootInvalidTemplate.setDefinition(new CpsFlowDefinition(
                " def srv = openstackMachine cloud: 'openstack', template: 'OtherTemplate'\n" +
                        " echo 'shouldnt reach' " , true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, bootInvalidTemplate.scheduleBuild2(0));
        j.assertLogNotContains("shouldnt reach", b);
        j.assertLogContains("Invalid template", b);

        assertThat(openstack.getRunningNodes(), emptyIterable());
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

        assertThat(openstack.getRunningNodes(), emptyIterable());
    }
}
