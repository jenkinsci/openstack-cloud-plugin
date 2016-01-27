package jenkins.plugins.openstack.compute;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.collection.IsArrayContainingInAnyOrder.arrayContainingInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class JCloudsBuildWrapperTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void provisionSeveral() throws Exception {
        final JCloudsCloud cloud = j.configureDummySlaveToBeProvisioned("label");
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
                String[] ips = build.getEnvVars().get("JCLOUDS_IPS").split(",");
                assertThat(ips, arrayWithSize(3));
                assertThat(ips, arrayContainingInAnyOrder("42.42.42.42", "42.42.42.42", "42.42.42.42"));
                return true;
            }
        });

        FreeStyleBuild build = j.buildAndAssertSuccess(p);

        verify(os, times(3)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        verify(os, times(3)).assignFloatingIp(any(Server.class));
        verify(os, times(3)).updateInfo(any(Server.class));
        verify(os, times(3)).destroyServer(any(Server.class));
        verifyNoMoreInteractions(os);
    }
}
