package jenkins.plugins.openstack.compute;

import java.lang.Math;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AsyncPeriodicWork;

/**
 * Periodically ensure enough slaves are created.
 */
@Extension @Restricted(NoExternalUse.class)
public final class JCloudsPreCreationThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(JCloudsPreCreationThread.class.getName());

    public JCloudsPreCreationThread() {
        super("OpenStack slave pre-creation");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN * 2;
    }

    @Override
    public void execute(TaskListener listener) {
        for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
            for (JCloudsSlaveTemplate template : cloud.getTemplates()) {
                SlaveOptions slaveOptions = template.getEffectiveSlaveOptions();
                int instancesMin = slaveOptions.getInstancesMin();
                int retentionTime = slaveOptions.getRetentionTime();
                if (instancesMin > 0) {
                    int globalMaxInstances = cloud.getEffectiveSlaveOptions().getInstanceCap();
                    int templateMaxInstances = slaveOptions.getInstanceCap();
                    int maxNodes = Math.min(templateMaxInstances, globalMaxInstances);
                    // If retentionTime==0, take this as an indication that "used" instances should not
                    // be reused and thus do not count them as running instances.
                    int runningNodeTotal = template.getActiveNodesTotal(retentionTime == 0);
                    int desiredNewInstances = Math.min(instancesMin, maxNodes) - runningNodeTotal;
                    if (desiredNewInstances > 0) {
                        LOGGER.log(Level.INFO, "Pre-creating " + desiredNewInstances + " instance(s) for template " + template.name + " in cloud " + cloud.name);
                        for (int i = 0; i < desiredNewInstances; i++) {
                            try {
                                cloud.doProvisionSlave(template);
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
    public static boolean shouldSlaveBeRetained(JCloudsSlave slave) {
        String templateName = slave.getId().getTemplateName();
        String cloudName = slave.getId().getCloudName();
        if (templateName != null && cloudName != null) {
            JCloudsCloud cloud = JCloudsCloud.getByName(cloudName);
            if (cloud != null) {
                JCloudsSlaveTemplate template = cloud.getTemplate(templateName);
                if (template != null) {
                    SlaveOptions slaveOptions = template.getEffectiveSlaveOptions();
                    Integer instancesMin = slaveOptions.getInstancesMin();
                    JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
                    Integer retentionTime = slaveOptions.getRetentionTime();
                    if (instancesMin > 0 && computer != null) {
                        if (retentionTime != 0 && (template.getActiveNodesTotal(false) - 1) < instancesMin) {
                            return true;
                        }
                        if (retentionTime == 0 && !computer.isUsed() && (template.getActiveNodesTotal(true) - 1) < instancesMin) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override protected Level getNormalLoggingLevel() { return Level.FINE; }
    @Override protected Level getSlowLoggingLevel() { return Level.INFO; }

}
