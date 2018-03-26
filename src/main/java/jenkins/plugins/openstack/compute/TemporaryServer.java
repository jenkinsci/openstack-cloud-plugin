package jenkins.plugins.openstack.compute;

import au.com.bytecode.opencsv.CSVReader;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Base64;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.api.Builders;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.Serializable;
import java.io.StringReader;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * For the purposes of Declarative Pipeline.
 * Most of the code is copied from @{@link JCloudsSlaveTemplate} class.
 * It was alternated to bypass template creation and to be able to create a machine directly from @{@link SlaveOptions}, without a template.
 * In the future they should be merged and implemented the way it would be working for both use-cases.
 */
@Restricted(NoExternalUse.class)
public class TemporaryServer implements Serializable {

    private static final Logger LOGGER = Logger.getLogger(TemporaryServer.class.getName());
    private static final char SEPARATOR_CHAR = ',';

    private static final AtomicInteger nodeCounter = new AtomicInteger();

    private SlaveOptions slaveOptions;
    private transient JCloudsCloud cloud;

    @DataBoundConstructor
    public TemporaryServer(final SlaveOptions slaveOptions) {
        this.slaveOptions = slaveOptions;
    }

    public @Nonnull SlaveOptions getRawSlaveOptions() {
        if (cloud == null) throw new IllegalStateException("Owner not set properly");
        return slaveOptions;
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
            @Nonnull JCloudsCloud cloud, @Nonnull ProvisioningActivity.Id id, TaskListener listener
    ) throws JCloudsCloud.ProvisioningFailedException, InterruptedException {
        int timeout = slaveOptions.getStartTimeout();
        this.cloud = cloud;
        Server nodeMetadata = provision(cloud);

        JCloudsSlave node = null;
        // Terminate node unless provisioned successfully
        try {
            node = new JCloudsSlave(id, nodeMetadata, "", slaveOptions);

            String cause;
            while ((cause = cloud.slaveIsWaitingFor(node)) != null) {
                if ((System.currentTimeMillis() - node.getCreatedTime()) > timeout) {
                    String timeoutMessage = String.format("Failed to connect agent %s within timeout (%d ms): %s", node.getNodeName(), timeout, cause);
                    LOGGER.warning(timeoutMessage);
                    throw new JCloudsCloud.ProvisioningFailedException(timeoutMessage);
                }

                Thread.sleep(2000);
            }

            return node;
        } catch (Throwable ex) {
            JCloudsCloud.ProvisioningFailedException cause = ex instanceof JCloudsCloud.ProvisioningFailedException
                    ? (JCloudsCloud.ProvisioningFailedException) ex
                    : new JCloudsCloud.ProvisioningFailedException("Unable to provision node: " + ex.getMessage(), ex)
                    ;

            if (node != null) {
                // No need to call AbstractCloudSlave#terminate() as this was never added to Jenkins
                node._terminate(TaskListener.NULL);
            }
            throw cause;
        }
    }

    /**
     * Provision OpenStack machine.
     *
     * @throws Openstack.ActionFailed In case the provisioning failed.
     * @see #provisionSlave(JCloudsCloud, ProvisioningActivity.Id, TaskListener)
     */
    /*package*/ @Nonnull Server provision(@Nonnull JCloudsCloud cloud) throws Openstack.ActionFailed {
        return provision(cloud, null);
    }

    public @Nonnull Server provision(@Nonnull JCloudsCloud cloud, @CheckForNull ServerScope scope) throws Openstack.ActionFailed {
        final String serverName = getServerName();
        final ServerCreateBuilder builder = Builders.server();

        if (scope == null) {
            scope = new ServerScope.Node(serverName);
        }
        builder.addMetadataItem(ServerScope.METADATA_KEY, scope.getValue());

        LOGGER.info("Provisioning new openstack server " + serverName + " with options " + slaveOptions);
        // Ensure predictable server name so we can inject it into user data
        builder.name(serverName);

        final Openstack openstack = cloud.getOpenstack();
        final BootSource bootSource = slaveOptions.getBootSource();
        if (bootSource == null) {
            LOGGER.warning("No " + BootSource.class.getSimpleName() + " set for " + getClass().getSimpleName() + " with name='" + null + "'.");
        } else {
            LOGGER.fine("Setting boot options to " + bootSource);
            bootSource.setServerBootSource(builder, openstack);
        }

        String hwid = slaveOptions.getHardwareId();
        if (!Strings.isNullOrEmpty(hwid)) {
            LOGGER.fine("Setting hardware Id to " + hwid);
            builder.flavor(hwid);
        }

        String nid = slaveOptions.getNetworkId();
        if (!Strings.isNullOrEmpty(nid)) {
            LOGGER.fine("Setting network to " + nid);
            builder.networks(Collections.singletonList(nid));
        }

        if (!Strings.isNullOrEmpty(slaveOptions.getSecurityGroups())) {
            LOGGER.fine("Setting security groups to " + slaveOptions.getSecurityGroups());
            for (String sg: csvToArray(slaveOptions.getSecurityGroups())) {
                builder.addSecurityGroup(sg);
            }
        }

        String kpn = slaveOptions.getKeyPairName();
        if (!Strings.isNullOrEmpty(kpn)) {
            LOGGER.fine("Setting keyPairName to " + kpn);
            builder.keypairName(kpn);
        }

        String az = slaveOptions.getAvailabilityZone();
        if (!Strings.isNullOrEmpty(az)) {
            LOGGER.fine("Setting availabilityZone to " + az);
            builder.availabilityZone(az);
        }

        @CheckForNull String userDataText = getUserData();
        if (userDataText != null) {
            String rootUrl = Jenkins.getActiveInstance().getRootUrl();
            UserDataVariableResolver resolver = new UserDataVariableResolver(rootUrl, serverName, null, slaveOptions);
            String content = Util.replaceMacro(userDataText, resolver);
            LOGGER.fine("Sending user-data:\n" + content);
            builder.userData(Base64.encode(content.getBytes(Charsets.UTF_8)));
        }

        Server server = openstack.bootAndWaitActive(builder, slaveOptions.getStartTimeout());

        try {
            if (bootSource != null) {
                bootSource.afterProvisioning(server, openstack);
            }
            String poolName = slaveOptions.getFloatingIpPool();
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
            String nameCandidate = "declarative-agent" + "-" + nodeCounter.getAndIncrement();

            // Collide with existing node - quite likely from this cloud
            if (Jenkins.getInstance().getNode(nameCandidate) != null) continue;

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

    private static String[] csvToArray(final String csv) {
        try {
            final CSVReader reader = new CSVReader(new StringReader(csv), SEPARATOR_CHAR);
            final String[] line = reader.readNext();
            return (line != null) ? line : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    /*package for testing*/ @CheckForNull String getUserData() {
        UserDataConfig.UserDataConfigProvider userDataConfigProvider = ConfigProvider.all().get(UserDataConfig.UserDataConfigProvider.class);
        if (userDataConfigProvider == null) throw new AssertionError("Openstack Config File Provider is not registered");
        Config userData = userDataConfigProvider.getConfigById(getRawSlaveOptions().getUserDataId());

        return (userData == null || userData.content.isEmpty())
                ? null
                : userData.content
                ;
    }
}
