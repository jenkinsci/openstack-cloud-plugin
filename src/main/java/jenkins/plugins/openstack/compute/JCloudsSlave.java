package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * Jenkins Slave node.
 */
public class JCloudsSlave extends AbstractCloudSlave implements TrackedItem {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());

    private final @Nonnull String cloudName;
    // Full/effective options
    private /*final*/ @Nonnull SlaveOptions options;
    private final @Nonnull ProvisioningActivity.Id provisioningId;

    private /*final*/ @Nonnull String nodeId;

    private final long created = System.currentTimeMillis();

    // Backward compatibility
    private transient @Deprecated int overrideRetentionTime;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated String slaveType; // converted to string for easier conversion
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
        setLauncher(new JCloudsLauncher(getLauncherFactory().createLauncher(this)));
    }

    // In 2.0, "nodeId" was removed and replaced by "metadata". Then metadata was deprecated in favour of "nodeId" again.
    // The configurations stored are expected to have at least one of them.
    @SuppressWarnings({"unused", "deprecation"})
    @SuppressFBWarnings({"RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", "The fields are non-null after readResolve"})
    protected Object readResolve() {
        super.readResolve();
        if (options == null) {
            // Node options are not of override of anything so we need to ensure this fill all mandatory fields
            // We base the outdated config on current plugin defaults to increase the chance it will work.
            SlaveOptions.Builder builder = JCloudsCloud.DescriptorImpl.getDefaultOptions().getBuilder()
                    .jvmOptions(Util.fixEmpty(jvmOptions))
            ;

            LauncherFactory lf = "SSH".equals(slaveType)
                    ? new LauncherFactory.SSH(credentialsId)
                    : LauncherFactory.JNLP.JNLP
            ;
            builder.launcherFactory(lf);

            if (overrideRetentionTime > 0) {
                builder = builder.retentionTime(overrideRetentionTime);
            }

            options = builder.build();
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
    	
        return Openstack.getPublicAddress(getOpenstack(cloudName).getServerById(nodeId));
    }
    /**
     * Get public IP address of the server.
     */
    @Restricted(NoExternalUse.class)
    public @CheckForNull String getPublicAddressIpv4() {
    	
        return Openstack.getPublicAddressIpv4(getOpenstack(cloudName).getServerById(nodeId));
    }

    /**
     * Get effective options used to configure this slave.
     */
    public @Nonnull SlaveOptions getSlaveOptions() {
        options.getClass();
        return options;
    }

    public @CheckForNull LauncherFactory getLauncherFactory() {
        return options.getLauncherFactory();
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

    public long getCreatedTime() {
        return created;
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
        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(this);
        if (activity != null) {
            activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
        }

        // Wrap deletion disposables into statistics tracking disposables
        AsyncResourceDisposer.get().dispose(
                new RecordDisposal(
                        new DestroyMachine(cloudName, nodeId),
                        provisioningId
                )
        );
    }

    private static Openstack getOpenstack(String cloudName) {
        return JCloudsCloud.getByName(cloudName).getOpenstack();
    }

    private final static class RecordDisposal implements Disposable {
        private final @Nonnull Disposable inner;
        private final @Nonnull ProvisioningActivity.Id provisioningId;

        private RecordDisposal(@Nonnull Disposable inner, @Nonnull ProvisioningActivity.Id provisioningId) {
            this.inner = inner;
            this.provisioningId = provisioningId;
        }

        @Override
        public @Nonnull State dispose() throws Throwable {
            try {
                return inner.dispose();
            } catch (Throwable ex) {
                CloudStatistics statistics = CloudStatistics.get();
                ProvisioningActivity activity = statistics.getActivityFor(provisioningId);
                if (activity != null) {
                    PhaseExecutionAttachment.ExceptionAttachment attachment = new PhaseExecutionAttachment.ExceptionAttachment(
                            ProvisioningActivity.Status.WARN, ex
                    );
                    statistics.attach(activity, ProvisioningActivity.Phase.COMPLETED, attachment);
                }
                throw ex;
            }
        }

        @Override
        public @Nonnull String getDisplayName() {
            return inner.getDisplayName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RecordDisposal that = (RecordDisposal) o;

            if (!inner.equals(that.inner)) return false;
            return provisioningId.equals(that.provisioningId);
        }

        @Override
        public int hashCode() {
            int result = inner.hashCode();
            result = 31 * result + provisioningId.hashCode();
            return result;
        }
    }
}
