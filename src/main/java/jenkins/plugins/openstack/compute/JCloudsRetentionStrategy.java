package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Descriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Vijay Kiran
 */
public class JCloudsRetentionStrategy extends RetentionStrategy<JCloudsComputer> {
    private transient ReentrantLock checkLock;
    private static List<JCloudsPreCreationThread.OldestVM> listOldestVm = new ArrayList<>();

    @DataBoundConstructor
    public JCloudsRetentionStrategy() {
        readResolve();
    }

    @Override
    public long check(JCloudsComputer c) {
        if (disabled) {
            LOGGER.fine("Skipping check - disabled");
            return 1;
        }
        LOGGER.fine("Checking");

        if (!checkLock.tryLock()) {
            LOGGER.info("Failed to acquire retention lock - skipping");
            return 1;
        }

        try {
            doCheck(c);
        } finally {
            checkLock.unlock();
        }
        return 1;
    }

    private void doCheck(JCloudsComputer c) {
        if (c.isPendingDelete()) return;
        if (c.isConnecting()) return; // Do not discard slave while launching for the first time when "idle time" does not make much sense
        final JCloudsSlave node = c.getNode();
        boolean oldestSlaveTermination = node.getSlaveOptions().getOldestSlaveTermination();

        //case where the checkbox isn't checked
        if (oldestSlaveTermination == false) {
            if (!c.isIdle() || c.getOfflineCause() instanceof OfflineCause.UserCause) return; // Occupied by user initiated activity

            if (node == null) return; // Node is gone already

            final int retentionTime = node.getSlaveOptions().getRetentionTime();

            if (retentionTime <= 0) return;  // 0 is handled in JCloudsComputer, negative values needs no handling
            final long idleSince = c.getIdleStart();
            final long idleMilliseconds = getNow() - idleSince;
            if (idleMilliseconds > TimeUnit.MINUTES.toMillis(retentionTime)) {
                if (JCloudsPreCreationThread.isNeededReadyComputer(node.getComputer())) {
                    LOGGER.info("Keeping " + c.getName() + " to meet minimum requirements");
                    return;
                }
                LOGGER.info("Scheduling " + c.getName() + " for termination after " + retentionTime + " minutes as it was idle since " + new Date(idleSince));
                if (LOGGER.isLoggable(Level.FINE)) {
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        Jenkins.XSTREAM2.toXMLUTF8(node, out);
                        LOGGER.fine(out.toString("UTF-8"));
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to dump node config", e);
                    }
                }
                c.setPendingDelete(true);
            }
        }

        //case where the checkbox is checked
        if ((oldestSlaveTermination == true)) {

            String templateName = c.getId().getTemplateName();
            String cloudName = c.getId().getCloudName();
            String oldestVmName = "";

            //number of free executors in our VM template
            int numOfFreeExec = JCloudsPreCreationThread.getNumOfFreeExec(templateName);

            //number of executor required for our template
            int numOfMinExec = c.getNode().getSlaveOptions().getNumExecutors() * node.getSlaveOptions().getInstancesMin();

            //If oldest VM list is empty
            if (listOldestVm.isEmpty()){
                //Find the oldest VM for the template of the current Computer
                oldestVmName = JCloudsPreCreationThread.getOldestVm(templateName, cloudName);
                //New object
                JCloudsPreCreationThread.OldestVM oldestVM = new JCloudsPreCreationThread.OldestVM(templateName, oldestVmName);
                //Add the object to the oldest VM list
                listOldestVm.add(oldestVM);
            } else {

                boolean templateInTheList = false;
                for (int i = 0; i<listOldestVm.size(); i++){
                    if (listOldestVm.get(i).getTemplateName().equals(templateName)){
                        templateInTheList = true;
                    }
                }
                //If the template is not in the oldestVMlist...
                if (templateInTheList == false){
                    //find the oldestVM for the template of the current computer
                    oldestVmName = JCloudsPreCreationThread.getOldestVm(templateName, cloudName);
                    //new object
                    JCloudsPreCreationThread.OldestVM oldestVM = new JCloudsPreCreationThread.OldestVM(templateName, oldestVmName);
                    //Add the object to the oldest VM list
                    listOldestVm.add(oldestVM);
                }
            }


            //If the number of free executors is higher than the number of minimum executors required
            //and
            //If the computer taken in parameter is the oldestVM

            if (numOfFreeExec > numOfMinExec){
                //If the current VM is the oldest
                for (int i = 0; i<listOldestVm.size(); i++){
                    if (templateName.equals(listOldestVm.get(i).templateName)){
                        if (listOldestVm.get(i).getOldestVmName().equals(c.getName())){
                            //We set the oldestVM to "PendingDelete"
                            c.setPendingDelete(true);
                            System.out.println(c.getName() + " Schedule for deleting");
                            //Find the oldestVM for this template
                            oldestVmName = JCloudsPreCreationThread.getOldestVm(templateName, cloudName);
                            //Set the new oldest VM in the list
                            listOldestVm.get(i).setOldestVmName(oldestVmName);
                            System.out.println("The oldest VM for the template "+templateName+" is now "+oldestVmName);

                        }
                    }
                }
            }

        }
    }

    /*package for mocking*/ long getNow() {
        return System.currentTimeMillis();
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(JCloudsComputer c) {
        c.connect(false);
    }

    // no @Extension since this retention strategy is used only for cloud nodes that we provision automatically.
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "JClouds";
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());

    @SuppressFBWarnings({"MS_SHOULD_BE_FINAL", "Left modifiable from groovy"})
    /*package*/ static boolean disabled = Boolean.getBoolean(JCloudsRetentionStrategy.class.getName() + ".disabled");
}
