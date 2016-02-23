package jenkins.plugins.openstack.compute;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.TestBuilder;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.collection.IsArrayContainingInAnyOrder.arrayContainingInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class JCloudsBuildWrapperTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void provisionSeveral() throws Exception {
        final JCloudsCloud cloud = j.createCloudProvisioningDummySlaves("label");
        Openstack os = cloud.getOpenstack();

        FreeStyleProject p = j.createFreeStyleProject();
        List<InstancesToRun> instances = Arrays.asList(
                new InstancesToRun("openstack", "template", null, 2),
                new InstancesToRun("openstack", "template", null, 1)
        );
        p.getBuildWrappersList().add(new JCloudsBuildWrapper(instances));
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                System.out.println();
                String[] ips = build.getEnvironment(TaskListener.NULL).get("JCLOUDS_IPS").split(",");
                assertThat(ips, arrayWithSize(3));
                assertThat(ips, arrayContainingInAnyOrder("42.42.42.0", "42.42.42.1", "42.42.42.2"));
                return true;
            }
        });

        j.buildAndAssertSuccess(p);

        verify(os, times(3)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        verify(os, times(3)).assignFloatingIp(any(Server.class));
        verify(os, times(3)).updateInfo(any(Server.class));
        verify(os, times(3)).destroyServer(any(Server.class));
        verifyNoMoreInteractions(os);
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/31")
    public void failToProvisionWhenOpenstackFails() throws Exception {
        JCloudsCloud cloud = j.dummyCloud(j.dummySlaveTemplate("label"));
        Openstack os = cloud.getOpenstack();

        Server success = j.mockServer().name("provisioned").floatingIp("42.42.42.42").get();

        // Fail the second invocation
        when(os.bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class)))
                .thenReturn(success)
                .thenThrow(new Openstack.ActionFailed("It is broken, alright!"))
        ;
        when(os.updateInfo(any(Server.class))).thenReturn(success);

        FreeStyleProject p = j.createFreeStyleProject();
        List<InstancesToRun> instances = Collections.singletonList(
                new InstancesToRun("openstack", "template", null, 2)
        );
        p.getBuildWrappersList().add(new JCloudsBuildWrapper(instances));

        FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains("One or more instances failed to launch", build);

        verify(os, times(6)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class)); // 5 retries on exception
        verify(os, times(1)).updateInfo(any(Server.class));
        verify(os, times(1)).assignFloatingIp(any(Server.class));
        verify(os, times(1)).destroyServer(any(Server.class)); // Cleanup after the successful attempt
        verifyNoMoreInteractions(os);
    }
}
