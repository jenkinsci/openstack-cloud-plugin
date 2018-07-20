package jenkins.plugins.openstack.compute;

import jenkins.plugins.openstack.compute.internal.Openstack;

import org.junit.Rule;
import org.junit.Test;

import jenkins.plugins.openstack.PluginTestRule;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class JCloudsPreCreationThreadTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void shouldSlaveBeRetainedTrueWhenInstancesAreRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        assertTrue("Slave must be retained", JCloudsPreCreationThread.shouldSlaveBeRetained(slave));
    }

	@Test
    public void shouldSlaveBeRetainedFalseWhenNoInstancesAreRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        assertFalse("Slave does not need to be retained", JCloudsPreCreationThread.shouldSlaveBeRetained(slave));
    }

    @Test
    public void createNodesWhenRequired() throws Exception {
        int minInstances = 5;
        int existingInstances = minInstances - 2;
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().instancesMin(minInstances).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();
        for (int i = 0; i < existingInstances; i++) {
            j.provision(cloud, "label");
        }
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
            j.provision(cloud, "label");
        }
        verify(os, times(existingInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));

        j.triggerSlavePreCreation();

        verify(os, times(existingInstances)).bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
    }
}
