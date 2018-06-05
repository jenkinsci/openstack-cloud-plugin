package jenkins.plugins.openstack.compute;

import jenkins.plugins.openstack.compute.internal.Openstack;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import jenkins.plugins.openstack.PluginTestRule;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class JCloudsPreCreationThreadTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void createNodesWhenRequired() throws Exception {
        int minInstances = 5;
        int existingInstances = minInstances - 2;
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().instancesMin(minInstances).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();
        for (int i = 0; i < existingInstances; i++) {
            JCloudsSlave slave = j.provision(cloud, "label");
            JCloudsComputer computer = (JCloudsComputer) slave.getComputer();
        }
        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(existingInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));

        j.triggerSlavePreCreation();

        verify(os, times(minInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
    }

    @Test
    public void doNotCreateNodesWhenUnnecessary() throws Exception {
        int minInstances = 1;
        int existingInstances = minInstances + 2;
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().instancesMin(minInstances).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();
        for (int i = 0; i < existingInstances; i++) {
            JCloudsSlave slave = j.provision(cloud, "label");
            JCloudsComputer computer = (JCloudsComputer) slave.getComputer();
        }
        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(existingInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));

        j.triggerSlavePreCreation();

        verify(os, times(existingInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
    }
}
