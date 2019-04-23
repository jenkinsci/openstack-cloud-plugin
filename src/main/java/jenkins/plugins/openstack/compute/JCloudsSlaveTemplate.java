package jenkins.plugins.openstack.compute;

import au.com.bytecode.opencsv.CSVReader;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Base64;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.openstack4j.api.Builders;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate>, SlaveOptions.Holder {

    // To be attached on all servers provisioned from configured templates
    public static final String OPENSTACK_CLOUD_NAME_KEY = "jenkins-cloud-name";
    // To be attached on all servers provisioned from configured templates
    public static final String OPENSTACK_TEMPLATE_NAME_KEY = "jenkins-template-name";

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());
    private static final char SEPARATOR_CHAR = ',';

    private static final AtomicInteger nodeCounter = new AtomicInteger();

    private final @Nonnull String name;
    private final @Nonnull String labelString;

    // Difference compared to cloud
    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    private transient Set<LabelAtom> labelSet;
    private /*final*/ transient JCloudsCloud cloud;

    // Backward compatibility
    private transient @Deprecated String imageId;
    private transient @Deprecated String hardwareId;
    private transient @Deprecated String userDataId;
    private transient @Deprecated String numExecutors;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String fsRoot;
    private transient @Deprecated Integer overrideRetentionTime;
    private transient @Deprecated String keyPairName;
    private transient @Deprecated String networkId;
    private transient @Deprecated String securityGroups;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated String slaveType; // Converted to string long after deprecated while converting enum to describable
    private transient @Deprecated String availabilityZone;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final @Nonnull String name, final @Nonnull String labels, final @CheckForNull SlaveOptions slaveOptions) {
        this.name = Util.fixNull(name).trim();
        this.labelString = Util.fixNull(labels).trim();

        this.slaveOptions = slaveOptions == null ? SlaveOptions.empty() : slaveOptions;

        readResolve();
    }

    @SuppressWarnings({"deprecation", "UnusedReturnValue"})
    private Object readResolve() {
        // Initializes data structure that we don't persist.
        labelSet = Label.parse(labelString);

        // Migrate from 1.X to 2.0
        int i;
        if (hardwareId != null && (i = hardwareId.indexOf('/')) != -1) {
            hardwareId = hardwareId.substring(i + 1);
        }

        if (networkId != null && (i = networkId.indexOf('/')) != -1) {
            networkId = networkId.substring(i + 1);
        }

        if (imageId != null && (i = imageId.indexOf('/')) != -1) {
            imageId = imageId.substring(i + 1);
        }

        // Migrate from 2.0 to 2.1
        if (slaveOptions == null) {
            LauncherFactory lf = null;
            if ("SSH".equals(slaveType) || credentialsId != null) {
                lf = new LauncherFactory.SSH(credentialsId);
            } else if("JNLP".equals(slaveType)) {
                lf = LauncherFactory.JNLP.JNLP;
            }

            BootSource.Image bs = imageId == null ? null : new BootSource.Image(imageId);
            slaveOptions = SlaveOptions.builder().bootSource(bs).hardwareId(hardwareId).numExecutors(Integer.getInteger(numExecutors)).jvmOptions(jvmOptions).userDataId(userDataId)
                    .fsRoot(fsRoot).retentionTime(overrideRetentionTime).keyPairName(keyPairName).networkId(networkId).securityGroups(securityGroups)
                    .launcherFactory(lf).availabilityZone(availabilityZone).build()
            ;

            this.hardwareId = null;
            this.numExecutors = null;
            this.jvmOptions = null;
            this.userDataId = null;
            this.fsRoot = null;
            this.overrideRetentionTime = null;
            this.keyPairName = null;
            this.networkId = null;
            this.securityGroups = null;
            this.credentialsId = null;
            this.slaveType = null;
            this.availabilityZone = null;
        }

        // Migrate from 2.24 to 2.25
        if (slaveOptions.slaveType != null) {
            LauncherFactory lf = null;
            if ("JNLP".equals(slaveOptions.slaveType)) {
                lf = LauncherFactory.JNLP.JNLP;
                slaveOptions.slaveType = null;
            } else if ("SSH".equals(slaveOptions.slaveType)) {
                lf = new LauncherFactory.SSH(slaveOptions.credentialsId);
                slaveOptions.slaveType = slaveOptions.credentialsId = null;
            }
            if (lf != null) {
                slaveOptions = slaveOptions.getBuilder().launcherFactory(lf).build();
            }
        }

        return this;
    }

    // Called when registered into cloud, this class is not supposed to be persisted before this is called
    @Restricted(NoExternalUse.class)
    /*package*/ void setOwner(JCloudsCloud cloud) {
        this.cloud = cloud;
        slaveOptions = slaveOptions.eraseDefaults(cloud.getEffectiveSlaveOptions());
    }

    public @Nonnull SlaveOptions getEffectiveSlaveOptions() {
        // Make sure only diff of defaults is saved so when defaults will change users are not stuck with outdated config
        return cloud.getEffectiveSlaveOptions().override(slaveOptions);
    }

    public @Nonnull SlaveOptions getRawSlaveOptions() {
        if (cloud == null) throw new IllegalStateException("Owner not set properly");
        return slaveOptions;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public @Nonnull String getName() {
        return name;
    }

    @Restricted(NoExternalUse.class) // Jelly + tests
    public @Nonnull String getLabels() {
        return labelString;
    }

    public boolean canProvision(final Label label) {
        return label == null || label.matches(labelSet);
    }

    /*package*/ boolean hasProvisioned(@Nonnull Server server) {
        return getName().equals(server.getMetadata().get(OPENSTACK_TEMPLATE_NAME_KEY));
    }

    /**
     * Provision and connect as a slave.
     *
     * The node is to be added to Jenkins by the caller. At the time the method completes, the node is ready to be launched.
     *
     * @throws Openstack.ActionFailed Provisioning failed.
     * @throws JCloudsCloud.ProvisioningFailedException Provisioning failed.
     */
    public @Nonnull JCloudsSlave provisionSlave(
            @Nonnull JCloudsCloud cloud, @Nonnull ProvisioningActivity.Id id
    ) throws JCloudsCloud.ProvisioningFailedException {
        SlaveOptions opts = getEffectiveSlaveOptions();
        int timeout = opts.getStartTimeout();
        Server server = provisionServer(null, id);

        JCloudsSlave node = null;
        // Terminate node unless provisioned successfully
        try {
            node = new JCloudsSlave(id, server, labelString, opts);

            String cause;
            while ((cause = cloud.slaveIsWaitingFor(node)) != null) {
                if ((System.currentTimeMillis() - node.getCreatedTime()) > timeout) {

                    String timeoutMessage = String.format("Failed to connect agent %s within timeout (%d ms): %s", node.getNodeName(), timeout, cause);
                    Error errorQuerying = null;
                    try {
                        Server freshServer = cloud.getOpenstack().getServerById(server.getId());
                        timeoutMessage += System.lineSeparator() + "Server state: " + freshServer;
                        // TODO attach instance log (or tail of) to cloud statistics
                    } catch (NoSuchElementException ex) {
                        timeoutMessage += System.lineSeparator() + "Server does no longer exist: " + server.getId();
                    } catch (Error ex) {
                        errorQuerying = ex;
                    }
                    LOGGER.warning(timeoutMessage);
                    JCloudsCloud.ProvisioningFailedException ex = new JCloudsCloud.ProvisioningFailedException(timeoutMessage);
                    if (errorQuerying != null) {
                        ex.addSuppressed(errorQuerying);
                    }
                    throw ex;
                }

                Thread.sleep(2000);
            }

            return node;
        } catch (Throwable ex) {
            JCloudsCloud.ProvisioningFailedException cause = ex instanceof JCloudsCloud.ProvisioningFailedException
                    ? (JCloudsCloud.ProvisioningFailedException) ex
                    : new JCloudsCloud.ProvisioningFailedException(ex.getMessage(), ex)
            ;

            if (node != null) {
                // No need to call AbstractCloudSlave#terminate() as this was never added to Jenkins
                node._terminate(TaskListener.NULL);
            }
            throw cause;
        }
    }

    @Restricted(NoExternalUse.class)
    public @Nonnull Server provisionServer(@CheckForNull ServerScope scope, @CheckForNull ProvisioningActivity.Id id) throws Openstack.ActionFailed {
        final String serverName = getServerName();
        final SlaveOptions opts = getEffectiveSlaveOptions();
        final ServerCreateBuilder builder = Builders.server();

        builder.addMetadataItem(OPENSTACK_TEMPLATE_NAME_KEY, getName());
        builder.addMetadataItem(OPENSTACK_CLOUD_NAME_KEY, cloud.name);
        if (scope == null) {
            scope = id == null
                    ? new ServerScope.Node(serverName)
                    : new ServerScope.Node(serverName, id)
            ;
        }
        builder.addMetadataItem(ServerScope.METADATA_KEY, scope.getValue());

        LOGGER.info("Provisioning new openstack server " + serverName + " with options " + opts);
        // Ensure predictable server name so we can inject it into user data
        builder.name(serverName);

        final Openstack openstack = cloud.getOpenstack();
        final BootSource bootSource = opts.getBootSource();
        if (bootSource == null) {
            LOGGER.warning("No " + BootSource.class.getSimpleName() + " set for " + getClass().getSimpleName() + " with name='" + getName() + "'.");
        } else {
            LOGGER.fine("Setting boot options to " + bootSource);
            bootSource.setServerBootSource(builder, openstack);
        }

        String hwid = opts.getHardwareId();
        if (!Strings.isNullOrEmpty(hwid)) {
            LOGGER.fine("Setting hardware Id to " + hwid);
            builder.flavor(hwid);
        }

        String nid = opts.getNetworkId();
        if (!Strings.isNullOrEmpty(nid)) {
            List<String> networks = openstack.getNetworkIds(Arrays.asList(csvToArray(nid)));
            LOGGER.fine("Setting networks to " + networks);
            builder.networks(networks);
        }

        String securityGroups = opts.getSecurityGroups();
        if (!Strings.isNullOrEmpty(securityGroups)) {
            LOGGER.fine("Setting security groups to " + securityGroups);
            for (String sg: csvToArray(securityGroups)) {
                builder.addSecurityGroup(sg);
            }
        }

        String kpn = opts.getKeyPairName();
        if (!Strings.isNullOrEmpty(kpn)) {
            LOGGER.fine("Setting keyPairName to " + kpn);
            builder.keypairName(kpn);
        }

        String az = opts.getAvailabilityZone();
        if (!Strings.isNullOrEmpty(az)) {
            LOGGER.fine("Setting availabilityZone to " + az);
            builder.availabilityZone(az);
        }

        @CheckForNull String userDataText = getUserData();
        if (userDataText != null) {
            String rootUrl = Jenkins.get().getRootUrl();
            UserDataVariableResolver resolver = new UserDataVariableResolver(rootUrl, serverName, labelString, opts);
            String content = Util.replaceMacro(userDataText, resolver);
            LOGGER.fine("Sending user-data:\n" + content);
            builder.userData(Base64.encode(content.getBytes(Charsets.UTF_8)));
        }

        Server server = openstack.bootAndWaitActive(builder, opts.getStartTimeout());

        try {
            if (bootSource != null) {
                bootSource.afterProvisioning(server, openstack);
            }
            String poolName = opts.getFloatingIpPool();
            if (poolName != null) {
                LOGGER.fine("Assigning floating IP from " + poolName + " to " + serverName);
                openstack.assignFloatingIp(server, poolName);
                // Make sure address information is reflected in metadata
                server = openstack.updateInfo(server);
                LOGGER.info("Amended server: " + server.toString());
            }

            LOGGER.info("Provisioned: " + server.toString());
            return server;
        } catch (Throwable ex) {
            // Do not leak the server as we are aborting the provisioning
            AsyncResourceDisposer.get().dispose(new DestroyMachine(cloud.name, server.getId()));
            throw ex;
        }
    }

    // Try harder to ensure node name is unique
    private String getServerName() {
        CloudStatistics cs = CloudStatistics.get();
        next_number: for (;;) {
            // Using static counter to ensure colliding template names (between clouds) will not cause a clash
            String nameCandidate = getName() + "-" + nodeCounter.getAndIncrement();

            // Collide with existing node - quite likely from this cloud
            if (Jenkins.get().getNode(nameCandidate) != null) continue;

            // Collide with node being provisioned (at this point this plugin does not assign final name before launch
            // is completed) or recently used name (just to avoid confusion).
            for (ProvisioningActivity provisioningActivity : cs.getActivities()) {
                if (nameCandidate.equals(provisioningActivity.getId().getNodeName())) {
                    continue next_number;
                }
            }
            return nameCandidate;
        }
    }

    private static @Nonnull String[] csvToArray(final String csv) {
        try {
            final CSVReader reader = new CSVReader(new StringReader(csv), SEPARATOR_CHAR);
            final String[] line = reader.readNext();
            return (line != null) ? line : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    /*package for testing*/ @CheckForNull String getUserData() {
        return UserDataConfig.resolve(getEffectiveSlaveOptions().getUserDataId());
    }

    /*package for testing*/ List<? extends Server> getRunningNodes() {
        List<Server> tmplt = new ArrayList<>();
        for (Server server : cloud.getOpenstack().getRunningNodes()) {
            if (hasProvisioned(server)) {
                tmplt.add(server);
            }
        }
        return tmplt;
    }

    /**
     * Return the number of active nodes provisioned using this template.
     */
    /*package*/ int getAvailableNodesTotal() {
        int totalServers = 0;

        for (JCloudsComputer computer : JCloudsComputer.getAll()) {
            ProvisioningActivity.Id cid = computer.getId();
            // Not this template
            if (!name.equals(cid.getTemplateName()) || !cloud.name.equals(cid.getCloudName())) continue;

            // Not active
            if (!computer.isIdle() || computer.isPendingDelete() || computer.getOfflineCause() instanceof OfflineCause.UserCause) continue;

            totalServers++;
        }
        return totalServers;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<JCloudsSlaveTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<JCloudsSlaveTemplate> {
        @Override
        public String getDisplayName() {
            return clazz.getSimpleName();
        }

        @Restricted(DoNotUse.class)
        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String value) {
            try {
                Jenkins.checkGoodName(value);
                return FormValidation.ok();
            } catch (Failure ex) {
                return FormValidation.error(ex.getMessage());
            }
        }
    }
}
