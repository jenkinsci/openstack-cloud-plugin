package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestExtension;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void scheduleSlaveDelete() throws Exception {
        int retentionTime = 1; // minute

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "template", "label", SlaveOptions.builder().retentionTime(retentionTime).build()
        );

        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(template));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        assertEquals(1, (int) slave.getSlaveOptions().getRetentionTime());

        JCloudsRetentionStrategy strategy = (JCloudsRetentionStrategy) slave.getRetentionStrategy();
        strategy.check(computer);
        assertFalse("Slave should not be scheduled for deletion right away", computer.isPendingDelete());

        Thread.sleep(1000 * 61); // Wait for the slave to be idle long enough

        strategy.check(computer);
        assertTrue("Slave should be scheduled for deletion", computer.isPendingDelete());
    }

    /**
     * There are several async operations taking place here:
     *
     * agent launch - in this case it JNLP noop implementation
     * slave connection activity - wrapper of agent launch with listeners called
     * openstack provisioning - that wait until the slave connection activity is done
     *
     * This tests simulates prolonged agent launch inserting delay in one of the listeners (in slave connection activity)
     * as we can not replace agent launch impl easily.
     *
     * @throws Exception
     */
    @Test
    public void doNotDeleteTheSlaveWhileLaunching() throws Exception {
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(j.dummySlaveTemplate(
                // no retention to make the slave disposable w.r.t retention time
                // give up soon enough to speed the test up
                j.dummySlaveOptions().getBuilder().retentionTime(0).startTimeout(3000).build(),
                "label"
        )));
        Collection<NodeProvisioner.PlannedNode> slaves = cloud.provision(Label.get("label"), 1);

        NodeProvisioner.PlannedNode node = slaves.iterator().next();
        assertFalse(node.future.isDone());

        // Wait for the future to create computer
        while (j.jenkins.getComputer("provisioned0") == null) {
            Thread.sleep(100);
        }

        JCloudsComputer computer = (JCloudsComputer) j.jenkins.getComputer("provisioned0");
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        computer.getRetentionStrategy().check(computer);

        // Still connecting after retention strategy run
        computer = (JCloudsComputer) j.jenkins.getComputer("provisioned0");
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        try {
            System.out.println("Waiting for timeout");
            node.future.get();
            fail("Expected to timeout");
        } catch (ExecutionException ex) {
            assertThat(ex.getCause(), instanceOf(JCloudsCloud.ProvisioningFailedException.class));
        }

        LaunchBlocker.unlock.signal();
    }
    @TestExtension("doNotDeleteTheSlaveWhileLaunching")
    public static class LaunchBlocker extends ComputerListener {
        private static OneShotEvent unlock = new OneShotEvent();
        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            unlock.block();
        }
    }

    @Test
    public void doNotDeleteSlavePutOfflineByUser() throws ExecutionException, InterruptedException {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                // no retention to make the slave disposable w.r.t retention time
                j.dummySlaveOptions().getBuilder().retentionTime(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(User.current(), "Offline"));

        computer.getRetentionStrategy().check(computer);
        assertFalse(computer.isPendingDelete());

        computer.setTemporarilyOffline(false, null);

        computer.getRetentionStrategy().check(computer);
        assertTrue(computer.isPendingDelete());
    }
}
