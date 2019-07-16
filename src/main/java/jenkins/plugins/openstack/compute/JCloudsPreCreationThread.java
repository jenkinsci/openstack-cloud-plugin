package jenkins.plugins.openstack.compute;

import java.lang.Math;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.Executor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import hudson.Extension;
import hudson.Functions;
import hudson.model.TaskListener;
import hudson.model.AsyncPeriodicWork;
import org.openstack4j.model.compute.Server;

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
                        cloud.provisionSlaveExplicitly(template);
                    } catch (Throwable ex) {
                        LOGGER.log(Level.SEVERE, "Failed to pre-create instance from template " + template.getName(), ex);
                    }
                }
            }
        }
    }

    /**
     * Methods which return the list of VM
     */
    public static List<? extends Server> getVmList(String templateName, String cloudName){
        List<? extends Server> vmList = null;
        for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
            if (cloud.getDisplayName().equals(cloudName)){
                vmList = cloud.getTemplate(templateName).getRunningNodes();
            }
        }
        return vmList;
    }

    /**
     * Methods which return the name of the oldestVM for a specific template.
     */
    public static String getOldestVm(String templateName, String cloudName){
        return getOldestVM(templateName, cloudName).getName();
    }


    /**
     * Methods which return the oldestVM
     * return a Server
     * return a VM which is not offline/Pending delete in Jenkins
     */
    private static Server getOldestVM(String templateName, String cloudName){
        Server oldestVm = null;
        long oldestVmTime;
        long currentVMTime;
        String computerName;
        String vmName;
        List<? extends Server> vmList = getVmList(templateName, cloudName);
        List<JCloudsComputer> listComputer = JCloudsComputer.getAll();
        if (oldestVm == null){
            oldestVm = vmList.get(0);
        }
        for (int i = 0; i<vmList.size(); i++){
            oldestVmTime = oldestVm.getCreated().getTime();
            currentVMTime = vmList.get(i).getCreated().getTime();
            if (oldestVmTime > currentVMTime){
                for (int y = 0; y<listComputer.size(); y++){
                    computerName = listComputer.get(y).getName();
                    vmName = vmList.get(i).getName();
                    if (computerName.equals(vmName)){
                        if (!listComputer.get(y).isOffline()){
                            oldestVm = vmList.get(i);
                        }
                    }
                }
            }
        }
        return oldestVm;
    }


    /**
     * Methods which return the number of free executors for a specific template
     * take the template name in parameter.
     */
    public static int getNumOfFreeExec(String templateName) {
        int nbFreeExecutors = 0;
        //List of Jenkins computer (VM into Jenkins)
        List<JCloudsComputer> listComputer = JCloudsComputer.getAll();
        List<Executor> listExecutors;
        //For each computer..
        for (int y=0; y<listComputer.size(); y++) {
            //If this Virtual machine was created with the Template in parameters
            //and
            //If this VM is connected
            if ((listComputer.get(y).getId().getTemplateName().equals(templateName)) && (listComputer.get(y).isOnline())){
                //We've get the VM executors into a List
                listExecutors = listComputer.get(y).getExecutors();
                //For each executors in the list
                for (int i = 0; i<listExecutors.size(); i++) {
                    //If the executor is free
                    if (listExecutors.get(i).isIdle()){
                        nbFreeExecutors = nbFreeExecutors + 1;
                    }
                }
            }
        }
        return nbFreeExecutors;
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

    public static class OldestVM {

        //attributes
        public String templateName;
        public String oldestVmName;

        //Constructor
        public OldestVM(String templateName, String oldestVmName){
            this.templateName = templateName;
            this.oldestVmName = oldestVmName;
        }

        public String getOldestVmName() {
            return oldestVmName;
        }

        public String getTemplateName() {
            return templateName;
        }

        public void setOldestVmName(String oldestVmName) {
            this.oldestVmName = oldestVmName;
        }

        public void setTemplateName(String templateName) {
            this.templateName = templateName;
        }
    }
}
