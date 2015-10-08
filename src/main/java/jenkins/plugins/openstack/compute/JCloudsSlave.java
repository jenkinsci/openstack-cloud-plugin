package jenkins.plugins.openstack.compute;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import jenkins.plugins.openstack.compute.internal.Openstack;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.model.compute.Server;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;

/**
 * Jenkins Slave node - managed by JClouds.
 *
 * @author Vijay Kiran
 */
public class JCloudsSlave extends AbstractCloudSlave {
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());
    private transient Server server;
    private final String cloudName;
    private boolean pendingDelete;
    private final int overrideRetentionTime;
    private final String jvmOptions;
    private final String credentialsId;
    private final JCloudsCloud.SlaveType slaveType;

    @DataBoundConstructor // TODO Is JCloudsSlave ever bound?
    @SuppressWarnings("rawtypes")
    public JCloudsSlave(String cloudName, String name, String remoteFS, String numExecutors, Mode mode, String labelString,
                        ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties,
                        int overrideRetentionTime, String jvmOptions, final String credentialsId, final JCloudsCloud.SlaveType slaveType) throws Descriptor.FormException,
            IOException {
        super(name, null, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.cloudName = cloudName;
        this.overrideRetentionTime = overrideRetentionTime;
        this.jvmOptions = jvmOptions;
        this.credentialsId = credentialsId;
        this.slaveType = slaveType;
    }

    /**
     * Constructs a new slave.
     *
     * @param cloudName             - the name of the cloud that's provisioning this slave.
     * @param fsRoot                - Location of Jenkins root (homedir) on the slave.
     * @param metadata              - node metadata
     * @param labelString           - Label(s) for this slave.
     * @param numExecutors          - Number of executors for this slave.
     * @param overrideRetentionTime - Retention time to use specifically for this slave, overriding the cloud default.
     * @param jvmOptions            - Custom options for lauching the JVM on the slave.
     * @param credentialsId         - Id of the credentials in Jenkin's global credentials database.
     * @throws IOException
     * @throws Descriptor.FormException
     */
    public JCloudsSlave(final String cloudName, final String fsRoot, Server metadata, final String labelString,
            final String numExecutors, final int overrideRetentionTime,
            String jvmOptions, final String credentialsId, final JCloudsCloud.SlaveType slaveType) throws IOException, Descriptor.FormException {
        this(cloudName, metadata.getName(), fsRoot, numExecutors, Mode.EXCLUSIVE, labelString,
                new JCloudsLauncher(), new JCloudsRetentionStrategy(), Collections.<NodeProperty<?>>emptyList(),
                overrideRetentionTime, jvmOptions, credentialsId, slaveType);
        this.server = metadata;
    }

    /**
     * Get Jclouds Custom JVM Options associated with this Slave.
     *
     * @return jvmOptions
     */
    public String getJvmOptions() {
        return jvmOptions;
    }

    /**
     * Get the retention time for this slave, defaulting to the parent cloud's if not set.
     * Sometime parent cloud cannot be determined (returns Null as I see), in which case this method will
     * return default value set in CloudInstanceDefaults.
     *
     * @return overrideTime
     * @see CloudInstanceDefaults#DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES
     */
    public int getRetentionTime() {
        /**
         * Checks if retention time for this slave is set.
         * -1 means - keep slave forever
         */
        if (overrideRetentionTime != 0) {
            return overrideRetentionTime;
        }

        JCloudsCloud cloud = JCloudsCloud.getByName(cloudName);
        return cloud == null ? DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES : cloud.getRetentionTime();
    }

    /**
     * Get the JClouds profile identifier for the Cloud associated with this slave.
     *
     * @return cloudName
     */
    public String getCloudName() {
        return cloudName;
    }

    public boolean isPendingDelete() {
        return pendingDelete;
    }

    public void setPendingDelete(boolean pendingDelete) {
        this.pendingDelete = pendingDelete;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public JCloudsCloud.SlaveType getSlaveType() {
        return slaveType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCloudComputer<JCloudsSlave> createComputer() {
        LOGGER.info("Creating a new JClouds Slave");
        return new JCloudsComputer(this);
    }

    /**
     * Get the potential addresses to connect to.
     */
    /*package*/ String[] getConnectionAddresses() {
        return new String[] {
                Openstack.getPublicAddress(server)
        };
    }

    @Extension
    public static final class JCloudsSlaveDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "JClouds Slave";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        Openstack os = JCloudsCloud.getByName(cloudName).getOpenstack();
        os.destroyServer(server);
    }
}
