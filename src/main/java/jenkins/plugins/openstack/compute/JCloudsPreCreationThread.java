package jenkins.plugins.openstack.compute;

import java.lang.Math;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
        HashMap<JCloudsSlaveTemplate, JCloudsCloud> requiredCapacity = new HashMap<>();
        for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
            for (JCloudsSlaveTemplate template : cloud.getTemplates()) {
                SlaveOptions to = template.getEffectiveSlaveOptions();
                if (to.getInstancesMin() > 0) {
                    requiredCapacity.put(template, cloud);
                }
            }
        }

        if (requiredCapacity.isEmpty()) return; // No capacity required anywhere

        for (Map.Entry<JCloudsSlaveTemplate, JCloudsCloud> entry : requiredCapacity.entrySet()) {
            JCloudsCloud cloud = entry.getValue();
            JCloudsSlaveTemplate template = entry.getKey();
            SlaveOptions so = template.getEffectiveSlaveOptions();
            Integer min = so.getInstancesMin();
            Integer cap = so.getInstanceCap();

            int available = template.getAvailableNodesTotal();
            if (available >= min) continue; // Satisfied
            if (available >= cap) continue; // Obey instanceCap even if instanceMin > instanceCap

            int runningNodes = template.getRunningNodes().size();

            if (runningNodes >= cap) continue; // Obey instanceCap

            int permitted = cap - runningNodes;
            int desired = min - available;
            int toProvision = Math.min(desired, permitted);
            if (toProvision > 0) {
                LOGGER.log(Level.INFO, "Pre-creating " + toProvision + " instance(s) for template " + template.getName() + " in cloud " + cloud.name);
                for (int i = 0; i < toProvision; i++) {
                    try {
                        cloud.provisionSlave(template);
                    } catch (Throwable ex) {
                        LOGGER.log(Level.SEVERE, "Failed to pre-create instance from template " + template.getName(), ex);
                    }
                }
            }
        }
    }

    /**
     * Should a slave be retained to meet the minimum instances constraint?
     *
     * @param computer Idle, not pending delete, not user offline but overdue w.r.t. retention time.
     */
    /*package*/ static boolean isNeededReadyComputer(JCloudsComputer computer) {
        if (computer == null) return false;

        Integer instancesMin = computer.getNode().getSlaveOptions().getInstancesMin();
        if (instancesMin > 0) {
            JCloudsCloud cloud = JCloudsCloud.getByName(computer.getId().getCloudName());
            String templateName = computer.getId().getTemplateName();
            JCloudsSlaveTemplate template = cloud.getTemplate(templateName);
            if (template != null) {
                int readyNodes = template.getAvailableNodesTotal();
                return readyNodes <= instancesMin;
            }
        }
        return false;
    }

    @Override protected Level getNormalLoggingLevel() { return Level.FINE; }
    @Override protected Level getSlowLoggingLevel() { return Level.INFO; }
}
