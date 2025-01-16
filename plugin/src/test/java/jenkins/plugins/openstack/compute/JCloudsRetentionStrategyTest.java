package jenkins.plugins.openstack.compute;

import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestExtension;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    private long checkAfter(JCloudsComputer computer, long milliseconds) {
        JCloudsRetentionStrategy ret = new JCloudsRetentionStrategy() {
            // Tweak the inner clock pretending the time has passed to speed things up
            @Override long getNow() {
                return System.currentTimeMillis() + milliseconds;
            }
        };

        return ret.check(computer);
    }

    @Test
    public void scheduleSlaveDelete() throws Exception {
        int retentionTime = 1; // minute

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "template", "label", SlaveOptions.builder().retentionTime(retentionTime).build()
        );

        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(template));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        assertEquals(1, (int) slave.getSlaveOptions().getRetentionTime());

        JCloudsRetentionStrategy strategy = (JCloudsRetentionStrategy) slave.getRetentionStrategy();
        strategy.check(computer);
        assertFalse("Slave should not be scheduled for deletion right away", computer.isPendingDelete());

        checkAfter(computer, 1000 * 61);

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
     */
    @Test
    public void doNotDeleteTheSlaveWhileLaunching() throws Exception {
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1 /*disposable asap*/).build(),
                "label"
        )));
        cloud.setCleanfreq(5);
        cloud.provision(Label.get("label"), 1);

        do {
            Thread.sleep(500);
        } while(Jenkins.get().getNodes().isEmpty());

        JCloudsSlave node = (JCloudsSlave) Jenkins.get().getNodes().get(0);
        JCloudsComputer computer = node.getComputer();
        assertSame(computer, j.jenkins.getComputer(node.getNodeName()));
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        checkAfter(computer, 70*1000);

        // Still connecting after retention strategy run
        computer = getNodeFor(node.getId());
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        LaunchBlocker.unlock.signal();
    }

    // Wait for the future to create computer
    private JCloudsComputer getNodeFor(ProvisioningActivity.Id id) throws InterruptedException {
        while (true) {
            for (Computer c: j.jenkins.getComputers()) {
                if (c instanceof JCloudsComputer) {
                    if (((JCloudsComputer) c).getId().equals(id)) {
                        return (JCloudsComputer) c;
                    }
                }
            }
            Thread.sleep(100);
        }
    }

    @TestExtension("doNotDeleteTheSlaveWhileLaunching")
    public static class LaunchBlocker extends ComputerListener {
        private static OneShotEvent unlock = new OneShotEvent();
        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws InterruptedException {
            unlock.block();
        }
    }

    @Test
    public void doNotDeleteSlavePutOfflineByUser() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();
        OfflineCause.UserCause userCause = new OfflineCause.UserCause(User.current(), "Offline");
        computer.setTemporarilyOffline(true, userCause);

        computer.getRetentionStrategy().check(computer);
        assertFalse(computer.isPendingDelete());
        assertEquals(userCause, computer.getOfflineCause());

        computer.setTemporarilyOffline(false, null);

        checkAfter(computer, 61*1000);

        assertTrue(computer.isPendingDelete());
    }

    @Test
    public void doNotScheduleForTerminationDuringLaunch() throws Exception {
        Assume.assumeFalse(Functions.isWindows());
        LauncherFactory launcherFactory = new TestCommandLauncherFactory("bash -c \"sleep 70 && java -jar '%s'\"");
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).launcherFactory(launcherFactory).build(),
                "label"
        )));
        j.provision(cloud, "label");

        while (true) {
            JCloudsComputer computer = waitForProvisionedComputer();
            computer.getRetentionStrategy().check(computer);
            computer.waitUntilOnline();
            // TODO current implementation prevents node to go offline during launch but the idle time still involves launch time
            if (computer.getChannel() != null) {
                break;
            }
            assertNull(computer.getOfflineCause());
            Thread.sleep(5000);
        }
    }

    @Test // Minimal positive retention time is 1 minute so we have to wait here more than that
    public void doNotRemoveSlaveShortlyAfterConnection() throws Exception {
        Assume.assumeFalse(Functions.isWindows());
        LauncherFactory launcherFactory = new TestCommandLauncherFactory("bash -c \"sleep 70 && java -jar '%s'\"");
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).launcherFactory(launcherFactory).build(),
                "label"
        )));

        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        while(computer.getChannel() == null){
            Thread.sleep(1000);
        }
        computer.getRetentionStrategy().check(computer);

        assertFalse(computer.isPendingDelete());
    }

    private JCloudsComputer waitForProvisionedComputer() throws InterruptedException {
        while (true) {
            List<JCloudsComputer> computers = JCloudsComputer.getAll();
            switch (computers.size()) {
                case 0: Thread.sleep(5_000); break;
                case 1: return computers.get(0);
                default: throw new AssertionError("More computers than expected " + computers);
            }
        }
    }

}
