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

    @Override protected Level getNormalLoggingLevel() { return Level.FINE; }
    @Override protected Level getSlowLoggingLevel() { return Level.INFO; }

}
