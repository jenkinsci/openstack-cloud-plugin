package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

/**
 * Jenkins Slave node.
 */
public class JCloudsSlave extends AbstractCloudSlave implements TrackedItem {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());

    private final @Nonnull String cloudName;
    private /*final*/ @Nonnull SlaveOptions options;
    private final ProvisioningActivity.Id provisioningId;

    private /*final*/ @Nonnull String nodeId;

    // Backward compatibility
    private transient @Deprecated int overrideRetentionTime;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated JCloudsCloud.SlaveType slaveType;
    private transient @Deprecated Server metadata;

    public JCloudsSlave(
            @Nonnull ProvisioningActivity.Id id, @Nonnull Server metadata, @Nonnull String labelString, @Nonnull SlaveOptions slaveOptions
    ) throws IOException, Descriptor.FormException {
        super(
                metadata.getName(),
                null,
                slaveOptions.getFsRoot(),
                slaveOptions.getNumExecutors(),
                Mode.NORMAL,
                labelString,
                null,
                new JCloudsRetentionStrategy(),
                Collections.singletonList(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("OPENSTACK_PUBLIC_IP", Openstack.getPublicAddress(metadata))
                ))
        );
        this.cloudName = id.getCloudName(); // TODO deprecate field
        this.provisioningId = id;
        this.options = slaveOptions;
        this.nodeId = metadata.getId();
        setLauncher(new JCloudsLauncher(getSlaveType().createLauncher(this)));
    }

    // In 2.0, "nodeId" was removed and replaced by "metadata". Then metadata was deprecated in favour of "nodeId" again.
    // The configurations stored are expected to have at least one of them.
    @SuppressWarnings({"unused", "deprecation"})
    @SuppressFBWarnings({"RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", "The fields are non-null after readResolve"})
    protected Object readResolve() {
        super.readResolve(); // Call parent
        if (options == null) {
            options = SlaveOptions.builder()
                    .retentionTime(overrideRetentionTime)
                    .jvmOptions(jvmOptions)
                    .credentialsId(credentialsId)
                    .slaveType(slaveType)
                    .build()
            ;
            jvmOptions = null;
            credentialsId = null;
            slaveType = null;
        }

        if (metadata != null && (nodeId == null || !nodeId.equals(metadata.getId()))) {
            nodeId = metadata.getId();
            metadata = null;
        }

        nodeId =  nodeId.replaceFirst(".*/", ""); // Remove region prefix

        return this;
    }

    /**
     * Get public IP address of the server.
     */
    public @CheckForNull String getPublicAddress() {
        return Openstack.getPublicAddress(getOpenstack().getServerById(nodeId));
    }

    /**
     * Get effective options used to configure this slave.
     */
    public @Nonnull SlaveOptions getSlaveOptions() {
        return options;
    }

    public JCloudsCloud.SlaveType getSlaveType() {
        return options.getSlaveType();
    }

    // Exposed for testing
    /*package*/ @Nonnull String getServerId() {
        return nodeId;
    }

    @Override
    public AbstractCloudComputer<JCloudsSlave> createComputer() {
        LOGGER.info("Creating a new computer for " + getNodeName());
        return new JCloudsComputer(this);
    }

    @Override
    public @Nonnull ProvisioningActivity.Id getId() {
        return this.provisioningId;
    }

    @Extension
    public static final class JCloudsSlaveDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "JClouds Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        try {
            getOpenstack().destroyServer(getOpenstack().getServerById(nodeId));
        } catch (NoSuchElementException ex) {
            // Already deleted
        } catch (Throwable ex) {
            CloudStatistics statistics = CloudStatistics.get();
            ProvisioningActivity activity = statistics.getActivityFor(this);
            if (activity != null) {
                activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
                statistics.attach(activity, ProvisioningActivity.Phase.COMPLETED, new PhaseExecutionAttachment.ExceptionAttachment(
                        ProvisioningActivity.Status.WARN, ex
                ));
            }
            throw ex;
        }
    }

    private Openstack getOpenstack() {
        return JCloudsCloud.getByName(cloudName).getOpenstack();
    }
}
