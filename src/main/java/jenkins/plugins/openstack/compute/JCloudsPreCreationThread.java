package jenkins.plugins.openstack.compute;

import java.lang.Math;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.Functions;
import hudson.model.TaskListener;
import hudson.model.AsyncPeriodicWork;

/**
 * Periodically ensure enough slaves are created.
 *
 * The goal of this class is to pre-provision slaves ahead of time to avoid jobs
 * having to wait until a slave gets provisioned to run.
 *
 * It works in conjunction with the logic in JCloudsRetentionStrategy to not
 * only pre-provision slaves but also keep the slaves around to meet
 * requirements.
 *
 * The behaviour is configured via the `instanceMin` setting which controls
 * how many instances per-template will be pre-provisioned.
 *
 * A template's retention time of 0 (zero) will be interpreted as a sign that
 * used instances shouldn't be re-used and thus new instances will be
 * pre-provisioned, even if used instances are running.
 *
 * The pre-provisioning always respects the instance capacity (either global or
 * per template).
 */
@Extension @Restricted(NoExternalUse.class)
public final class JCloudsPreCreationThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(JCloudsPreCreationThread.class.getName());

    public JCloudsPreCreationThread() {
        super("OpenStack slave pre-creation");
    }

    @Override
    public long getRecurrencePeriod() {
        return Functions.getIsUnitTest() ? Long.MAX_VALUE : MIN * 2;
    }

    @Override
    public void execute(TaskListener listener) {
        for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
            for (JCloudsSlaveTemplate template : cloud.getTemplates()) {
                SlaveOptions slaveOptions = template.getEffectiveSlaveOptions();
                int instancesMin = slaveOptions.getInstancesMin();
                if (instancesMin > 0) {
                    int globalMaxInstances = cloud.getEffectiveSlaveOptions().getInstanceCap();
                    int templateMaxInstances = slaveOptions.getInstanceCap();
                    int maxNodes = Math.min(templateMaxInstances, globalMaxInstances);
                    // If retentionTime==0, take this as an indication that "used" instances should not
                    // be reused and thus do not count them as reusable running instances.
                    int reusableRunningNodeTotal = template.getActiveNodesTotal(slaveOptions.getRetentionTime() == 0);
                    int runningNodeTotal = template.getActiveNodesTotal(false);
                    int desiredNewInstances = Math.min(instancesMin - reusableRunningNodeTotal, maxNodes - runningNodeTotal);
                    if (desiredNewInstances > 0) {
                        LOGGER.log(Level.INFO, "Pre-creating " + desiredNewInstances + " instance(s) for template " + template.name + " in cloud " + cloud.name);
                        for (int i = 0; i < desiredNewInstances; i++) {
                            try {
                                cloud.provisionSlave(template);
                            } catch (Throwable ex) {
                                LOGGER.log(Level.SEVERE, "Failed to pre-create instance from template " + template.name, ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
    * Should a slave be retained to meet the minimum instances constraint?
    */
    /*package*/ static boolean shouldSlaveBeRetained(JCloudsSlave slave) {
        String templateName = slave.getId().getTemplateName();
        String cloudName = slave.getId().getCloudName();
        if (templateName != null) {
            JCloudsCloud cloud = JCloudsCloud.getByName(cloudName);
            JCloudsSlaveTemplate template = cloud.getTemplate(templateName);
            if (template != null) {
                SlaveOptions slaveOptions = template.getEffectiveSlaveOptions();
                Integer instancesMin = slaveOptions.getInstancesMin();
                JCloudsComputer computer = slave.getComputer();
                Integer retentionTime = slaveOptions.getRetentionTime();
                if (instancesMin > 0 && computer != null) {
                    if (retentionTime != 0 && (template.getActiveNodesTotal(false) - 1) < instancesMin) {
                        return true;
                    }
                    return retentionTime == 0 && !computer.isUsed() && (template.getActiveNodesTotal(true) - 1) < instancesMin;
                } else {
                    Jenkins instanceOrNull = Jenkins.getInstanceOrNull();
                    if (computer != null && retentionTime == 0 && instanceOrNull != null) {
                        //check if there is a task in the queue - retentionTime=0 && instancesMin<0 can cause removal of computer before it was ever used.
                        return !instanceOrNull.getQueue().getBuildableItems(computer).isEmpty();
                    }
                }
            }
        }
        return false;
    }

    @Override protected Level getNormalLoggingLevel() { return Level.FINE; }
    @Override protected Level getSlowLoggingLevel() { return Level.INFO; }
}
