package jenkins.plugins.openstack.compute;

import hudson.EnvVars;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.hamcrest.MatcherAssert;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestExtension;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

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
     */
    @Test
    public void doNotDeleteTheSlaveWhileLaunching() throws Exception {
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1) // disposable asap
                        //.startTimeout(3000) // give up soon enough to speed the test up
                        .build(),
                "label"
        )));
        cloud.provision(Label.get("label"), 1);

        do {
            Thread.sleep(500);
        } while(Jenkins.get().getNodes().isEmpty());

        JCloudsSlave node = (JCloudsSlave) Jenkins.get().getNodes().get(0);
        JCloudsComputer computer = node.getComputer();
        assertSame(computer, j.jenkins.getComputer(node.getNodeName()));
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        Thread.sleep(60*1000); // Sleep long enough for retention time to expire

        computer.getRetentionStrategy().check(computer);

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
        Thread.sleep(60*1000); // Wait for shortest amount possible for retentions strategy

        computer.getRetentionStrategy().check(computer);
        assertTrue(computer.isPendingDelete());
    }

    @Test
    public void doNotScheduleForTerminationDuringLaunch() throws Exception {
        Assume.assumeFalse(Functions.isWindows());
        LauncherFactory launcherFactory = new CommandLauncherFactory();
        j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).launcherFactory(launcherFactory).build(),
                "label"
        )));

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);

        JCloudsComputer computer = waitForProvisionedComputer();
        assertTrue(computer.isConnecting());

        FreeStyleBuild build;
        while (true) {
            computer.getRetentionStrategy().check(computer);
            try {
                build = f.get(5, TimeUnit.SECONDS);
                break;
            } catch (TimeoutException e) {
                // continue waiting
            }
        }

        j.assertBuildStatusSuccess(build);
        MatcherAssert.assertThat(build.getBuiltOn(), equalTo(computer.getNode()));
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

    private static class CommandLauncherFactory extends LauncherFactory {
        private static final long serialVersionUID = -1430772041065953918L;

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) {
            return new CommandLauncher(
                    String.format("bash -c \"sleep 70 && java -jar '%s'\"",getAbsolutePath()),
                    new EnvVars());
        }

        private static @Nonnull String getAbsolutePath() {
            try {
                return new File(Jenkins.get().getJnlpJars("slave.jar").getURL().toURI()).getAbsolutePath();
            } catch (URISyntaxException | IOException e) {
                throw new Error(e);
            }
        }

        @Override
        public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) throws JCloudsCloud.ProvisioningFailedException {
            return null;
        }
    }
}
