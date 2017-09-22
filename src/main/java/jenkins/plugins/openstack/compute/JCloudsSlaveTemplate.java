package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import hudson.remoting.Base64;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.Builders;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import com.google.common.base.Strings;

import au.com.bytecode.opencsv.CSVReader;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

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

    public final String name;
    public final String labelString;

    // Difference compared to cloud
    private /*final*/ SlaveOptions slaveOptions;

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
    public JCloudsSlaveTemplate(final String name, final String labelString, final SlaveOptions slaveOptions) {

        this.name = Util.fixEmptyAndTrim(name);
        this.labelString = Util.fixNull(labelString);

        this.slaveOptions = slaveOptions;

        readResolve();
    }

    @SuppressWarnings("deprecation")
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

            slaveOptions = SlaveOptions.builder().imageId(imageId).hardwareId(hardwareId).numExecutors(Integer.getInteger(numExecutors)).jvmOptions(jvmOptions).userDataId(userDataId)
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

    public boolean canProvision(final Label label) {
        return label == null || label.matches(labelSet);
    }

    /*package*/ boolean hasProvisioned(@Nonnull Server server) {
        return name.equals(server.getMetadata().get(OPENSTACK_TEMPLATE_NAME_KEY));
    }

    /**
     * Provision and connect as a slave.
     *
     * The node is to be added to Jenkins by the caller. At the time the method completes, the node is ready to be launched.
     *
     * @throws Openstack.ActionFailed Provisioning failed.
     */
    public @Nonnull JCloudsSlave provisionSlave(
            @Nonnull JCloudsCloud cloud, @Nonnull ProvisioningActivity.Id id, @Nonnull TaskListener listener
    ) throws JCloudsCloud.ProvisioningFailedException, InterruptedException {
        Server nodeMetadata = provision(cloud);
        SlaveOptions opts = getEffectiveSlaveOptions();

        try {
            JCloudsSlave node = new JCloudsSlave(id, nodeMetadata, labelString, opts);

            int timeout = node.getSlaveOptions().getStartTimeout();
            String timeoutMessage = String.format("Failed to connect to slave %s within timeout (%d ms).", node.getNodeName(), timeout);
            while (!cloud.isSlaveReadyToLaunch(node)) {
                if ((System.currentTimeMillis() - node.getCreatedTime()) > timeout) {
                    LOGGER.warning(timeoutMessage);
                    node.terminate();
                    throw new JCloudsCloud.ProvisioningFailedException(timeoutMessage);
                }

                Thread.sleep(2000);
            }

            return node;
        } catch (Descriptor.FormException e) {
            throw new JCloudsCloud.ProvisioningFailedException("Invalid configuration " + e.getMessage(), e);
        } catch (IOException e) {
            throw new JCloudsCloud.ProvisioningFailedException("Unable to provision node", e);
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

    @Restricted(NoExternalUse.class)
    public @Nonnull Server provision(@Nonnull JCloudsCloud cloud, @CheckForNull ServerScope scope) throws Openstack.ActionFailed {
        final String serverName = name + "-" + new Random().nextInt(10000);
        final SlaveOptions opts = getEffectiveSlaveOptions();
        final ServerCreateBuilder builder = Builders.server();

        builder.addMetadataItem(OPENSTACK_TEMPLATE_NAME_KEY, name);
        builder.addMetadataItem(OPENSTACK_CLOUD_NAME_KEY, cloud.name);
        if (scope == null) {
            scope = new ServerScope.Node(serverName);
        }
        builder.addMetadataItem(ServerScope.METADATA_KEY, scope.getValue());

        LOGGER.info("Provisioning new openstack server " + serverName + " with options " + opts);
        // Ensure predictable server name so we can inject it into user data
        builder.name(serverName);

        if (!Strings.isNullOrEmpty(opts.getImageId())) {
            String imageId = cloud.getOpenstack().getImageIdFor(opts.getImageId());
            LOGGER.fine("Setting image id to " + imageId);
            builder.image(imageId);
        }

        String hwid = opts.getHardwareId();
        if (!Strings.isNullOrEmpty(hwid)) {
            LOGGER.fine("Setting hardware Id to " + hwid);
            builder.flavor(hwid);
        }

        String nid = opts.getNetworkId();
        if (!Strings.isNullOrEmpty(nid)) {
            LOGGER.fine("Setting network to " + nid);
            builder.networks(Collections.singletonList(nid));
        }

        if (!Strings.isNullOrEmpty(opts.getSecurityGroups())) {
            LOGGER.fine("Setting security groups to " + opts.getSecurityGroups());
            for (String sg: csvToArray(opts.getSecurityGroups())) {
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
            String rootUrl = Jenkins.getActiveInstance().getRootUrl();
            UserDataVariableResolver resolver = new UserDataVariableResolver(rootUrl, serverName, labelString, opts);
            String content = Util.replaceMacro(userDataText, resolver);
            LOGGER.fine("Sending user-data:\n" + content);
            builder.userData(Base64.encode(content.getBytes(Charsets.UTF_8)));
        }

        final Openstack openstack = cloud.getOpenstack();
        Server server = openstack.bootAndWaitActive(builder, opts.getStartTimeout());
        LOGGER.info("Provisioned: " + server.toString());

        String poolName = opts.getFloatingIpPool();
        if (poolName != null) {
            LOGGER.fine("Assigning floating IP from " + poolName + " to " + serverName);
            try {
                openstack.assignFloatingIp(server, poolName);
                // Make sure address information is reflected in metadata
                server = openstack.updateInfo(server);
                LOGGER.info("Amended server: " + server.toString());
            } catch (Throwable ex) {
                // Do not leak the server as we are aborting the provisioning
                AsyncResourceDisposer.get().dispose(new DestroyMachine(cloud.name, server.getId()));
                throw ex;
            }
        }

        return server;
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
        Config userData = userDataConfigProvider.getConfigById(getEffectiveSlaveOptions().getUserDataId());

        return (userData == null || userData.content.isEmpty())
                ? null
                : userData.content
        ;
    }

    /*package for testing*/ List<? extends Server> getRunningNodes() {
        List<Server> tmplt = new ArrayList<>();
        for (Server server : cloud.getOpenstack().getRunningNodes()) {
            Map<String, String> md = server.getMetadata();
            if (name.equals(md.get(OPENSTACK_TEMPLATE_NAME_KEY))) {
                tmplt.add(server);
            }
        }
        return tmplt;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<JCloudsSlaveTemplate> getDescriptor() {
        return Jenkins.getActiveInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<JCloudsSlaveTemplate> {
        private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][-a-zA-Z0-9]{0,79}");

        @Override
        public String getDisplayName() {
            return clazz.getSimpleName();
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckName(@QueryParameter String value) {
            if (NAME_PATTERN.matcher(value).find()) return FormValidation.ok();

            return FormValidation.error(
                    "Must be a lowercase string, from 1 to 80 characteres long, starting with letter or number"
            );
        }
    }

}
