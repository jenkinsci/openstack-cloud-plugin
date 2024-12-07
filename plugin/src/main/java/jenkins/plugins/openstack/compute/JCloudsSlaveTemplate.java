package jenkins.plugins.openstack.compute;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.internal.TokenGroup;
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
import org.openstack4j.model.network.Network;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate>, SlaveOptions.Holder {

    // To be attached on all servers provisioned from configured templates
    public static final String OPENSTACK_CLOUD_NAME_KEY = "jenkins-cloud-name";
    // To be attached on all servers provisioned from configured templates
    public static final String OPENSTACK_TEMPLATE_NAME_KEY = "jenkins-template-name";
    // To be attached on all servers provisioned from configured templates
    public static final String OPENSTACK_NETWORK_ORDER = "jenkins-network-order";

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());

    private static final AtomicInteger nodeCounter = new AtomicInteger();
    // Default number of milliseconds to sleep before checking again if provisioning completed.
    private static final int pollingPeriodWhileWaitingForProvisioning = 6000;

    private final @Nonnull String name;
    private final @Nonnull String labelString;

    // Difference compared to cloud
    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    private transient Set<LabelAtom> labelSet;
    private /*final*/ transient JCloudsCloud cloud;

    // Backward compatibility
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String imageId;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String hardwareId;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String userDataId;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String numExecutors;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String jvmOptions;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String fsRoot;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") Integer overrideRetentionTime;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String keyPairName;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String networkId;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String securityGroups;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String slaveType; // Converted to string long after deprecated while converting enum to describable
    private transient @Deprecated @SuppressWarnings("DeprecatedIsStillUsed") String availabilityZone;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final @Nonnull String name, final @Nonnull String labels, final @CheckForNull SlaveOptions slaveOptions) {
        this.name = Util.fixNull(name).trim();
        this.labelString = Util.fixNull(labels).trim();

        this.slaveOptions = slaveOptions == null ? SlaveOptions.empty() : slaveOptions;

        readResolve();
    }

    @SuppressWarnings({"deprecation", "UnusedReturnValue", "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"})
    @SuppressFBWarnings({"deprecation", "UnusedReturnValue", "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"})
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
                if (node.isLaunchTimedOut()) {

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

                Thread.sleep(pollingPeriodWhileWaitingForProvisioning);
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
    public @Nonnull Server provisionServer(
            @CheckForNull ServerScope scope, @CheckForNull ProvisioningActivity.Id id
    ) throws Openstack.ActionFailed {
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
        if (Util.fixEmpty(hwid) != null) {
            LOGGER.fine("Setting hardware Id to " + hwid);
            builder.flavor(hwid);
        }

        String nid = opts.getNetworkId();
        if (Util.fixEmpty(nid) != null) {
            List<String> networks = selectNetworkIds(openstack, nid);
            LOGGER.fine("Setting networks to " + networks);
            builder.networks(networks);
            builder.addMetadataItem(OPENSTACK_NETWORK_ORDER, String.join(",", selectNetworkOrder(openstack, nid)));
        }

        String securityGroups = opts.getSecurityGroups();
        if (Util.fixEmpty(securityGroups) != null) {
            LOGGER.fine("Setting security groups to " + securityGroups);
            for (String sg: parseSecurityGroups(securityGroups)) {
                builder.addSecurityGroup(sg);
            }
        }

        String kpn = opts.getKeyPairName();
        if (Util.fixEmpty(kpn) != null) {
            LOGGER.fine("Setting keyPairName to " + kpn);
            builder.keypairName(kpn);
        }

        String az = opts.getAvailabilityZone();
        if (Util.fixEmpty(az) != null) {
            LOGGER.fine("Setting availabilityZone to " + az);
            builder.availabilityZone(az);
        }

        @CheckForNull String userDataText = getUserData();
        if (userDataText != null) {
            String rootUrl = Util.fixNull(Jenkins.get().getRootUrl());
            UserDataVariableResolver resolver = new UserDataVariableResolver(rootUrl, serverName, labelString, opts);
            String content = Util.replaceMacro(userDataText, resolver);
            assert content != null;

            LOGGER.fine("Sending user-data:\n" + content);
            byte[] binaryData = content.getBytes(StandardCharsets.UTF_8);
            String result = java.util.Base64.getEncoder().encodeToString(binaryData);
            builder.userData(result);
        }

        Boolean configDrive = opts.getConfigDrive();
        if (configDrive != null) {
            builder.configDrive(configDrive);
        }

        Server server = openstack.bootAndWaitActive(builder, opts.getStartTimeout());
        try {
            if (bootSource != null) {
                bootSource.afterProvisioning(server, openstack);
            }
            LOGGER.info("Provisioned: " + server);
            String poolName = opts.getFloatingIpPool();
            if (poolName != null) {
                LOGGER.fine("Assigning floating IP from " + poolName + " to " + serverName);
                server = openstack.assignFloatingIp(server, poolName);
                LOGGER.info("Amended server: " + server);
            }

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

    @VisibleForTesting
    /*package*/ static @Nonnull List<String> parseSecurityGroups(@Nonnull String securityGroups) {
        if (securityGroups == null || securityGroups.isEmpty()) throw new IllegalArgumentException();

        List<String> from = TokenGroup.from(securityGroups, ',');
        if (from.isEmpty() || from.contains("")) {
            throw new IllegalArgumentException("Security group declaration contains blank '" + securityGroups + "'");
        }
        return from;
    }

    /**
     * Transform networks spec into a list of IDs to actually use.
     *
     * @return List of network IDs to connect to.
     */
    @VisibleForTesting
    /*package*/ static @Nonnull List<String> selectNetworkIds(@Nonnull Openstack openstack, @Nonnull String spec) {
        if (spec == null || spec.isEmpty()) throw new IllegalArgumentException();

        List<List<String>> declared = TokenGroup.from(spec, ',', '|');

        List<String> allDeclaredNetworks = declared.stream().flatMap(Collection::stream).collect(Collectors.toList());

        if (declared.isEmpty() || declared.contains(Collections.emptyList()) || allDeclaredNetworks.contains("")) {
            throw new IllegalArgumentException("Networks declaration contains blank '" + declared + "'");
        }

        Map<String, Network> osNetworksById = openstack.getNetworks(allDeclaredNetworks);
        Map<String, Network> osNetworksByName = osNetworksById.values().stream().collect(Collectors.toMap(Network::getName, n -> n));

        final Function<String, String> RESOLVE_NAMES_TO_IDS = n -> {
            if (osNetworksById.containsKey(n)) return n;
            Network network = osNetworksByName.getOrDefault(n, null);
            if (network != null) return network.getId();
            throw new IllegalArgumentException("No network '" + n + "' found for " + spec);
        };

        // Do not even consult capacity when there are no alternatives declared
        if (!spec.contains("|")) {
            return allDeclaredNetworks.stream().map(RESOLVE_NAMES_TO_IDS).collect(Collectors.toList());
        }

        Map<Network, Integer> capacities = openstack.getNetworksCapacity(osNetworksById);
        if (capacities.isEmpty()) {
            LOGGER.warning("OpenStack network-ip-availability endpoint is inaccessible, unable to balance the load for " + spec);
            // Return first of the alternatives
            return declared.stream().map(l -> l.get(0)).map(RESOLVE_NAMES_TO_IDS).collect(Collectors.toList());
        }

        ArrayList<Network> ret = new ArrayList<>(declared.size());
        for (List<String> alternativeList : declared) { // All networks to connect to

            Optional<Network> emptiest = alternativeList.stream().map(RESOLVE_NAMES_TO_IDS).map(osNetworksById::get).min((l, r) -> {
                Integer lCap = capacities.get(l);
                Integer rCap = capacities.get(r);
                return rCap.compareTo(lCap);
            });

            assert emptiest.isPresent(): "Alternative set empty";

            Network network = emptiest.get();
            ret.add(network);
        }

        Function<Network, String> DESCRIBE_NETWORK = n -> n.getName() + "/" + n.getId();
        Map<String, Integer> userTokenBasedCapacities = capacities.keySet().stream().collect(Collectors.toMap(
                DESCRIBE_NETWORK,
                capacities::get
        ));
        List<String> networks = ret.stream().map(DESCRIBE_NETWORK).collect(Collectors.toList());
        LOGGER.fine("Resolving network spec '" + spec + "' to '" + networks + " given free capacity " + userTokenBasedCapacities);

        // Report shortage
        Map<String, Long> exhaustedPools = ret.stream().collect(
                // Group selected networks by requested capacity
                Collectors.groupingBy(n -> n, Collectors.counting())
        ).entrySet().stream().filter(
                // Filter those with insufficient capacity
                nle -> capacities.get(nle.getKey()) < nle.getValue()
        ).collect(Collectors.toMap(
                // name -> capacity
                nle -> nle.getKey().getName() + "/" + nle.getKey().getId(),
                Map.Entry::getValue
        ));
        if (!exhaustedPools.isEmpty()) {
            LOGGER.warning("Not enough fixed IPs for " + spec + " with capacity " + exhaustedPools);
        }

        return ret.stream().map(Network::getId).collect(Collectors.toList());
    }
    @VisibleForTesting
    /*package*/ static @Nonnull List<String> selectNetworkOrder(@Nonnull Openstack openstack, @Nonnull String spec) {
        if (spec == null || spec.isEmpty()) throw new IllegalArgumentException();

        List<List<String>> declared = TokenGroup.from(spec, ',', '|');

        List<String> allDeclaredNetworks = declared.stream().flatMap(Collection::stream).collect(Collectors.toList());

        if (declared.isEmpty() || declared.contains(Collections.emptyList()) || allDeclaredNetworks.contains("")) {
            throw new IllegalArgumentException("Networks declaration contains blank '" + declared + "'");
        }

        Map<String, Network> osNetworksById = openstack.getNetworks(allDeclaredNetworks);
        Map<String, Network> osNetworksByName = osNetworksById.values().stream().collect(Collectors.toMap(Network::getName, n -> n));

        final Function<String, String> RESOLVE_IDS_TO_NAME = n -> {
            if (osNetworksByName.containsKey(n)) return n;
            Network network = osNetworksById.getOrDefault(n, null);
            if (network != null) return network.getName();
            throw new IllegalArgumentException("No network name '" + n + "' found for " + spec);
        };

        return allDeclaredNetworks.stream().map(RESOLVE_IDS_TO_NAME).distinct().collect(Collectors.toList());
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
            if (!computer.isIdle() || computer.isPendingDelete() || computer.isUserOffline()) continue;

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
        @Nonnull
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
