package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.Builders;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.image.Image;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import com.trilead.ssh2.Connection;

import au.com.bytecode.opencsv.CSVReader;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;

import javax.annotation.Nonnull;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate> {

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());
    private static final char SEPARATOR_CHAR = ',';

    public final String name;
    public final String imageId;
    public final String hardwareId;
    public final String labelString;
    public final String userDataId;
    public final String numExecutors;
    private final String jvmOptions;
    private final String fsRoot;
    public final boolean installPrivateKey;
    public final int overrideRetentionTime;
    public final String keyPairName;
    public final String networkId;
    public final String securityGroups;
    public final String credentialsId;
    public final JCloudsCloud.SlaveType slaveType;
    public final String availabilityZone;

    private transient Set<LabelAtom> labelSet;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final String name, final String imageId, final String hardwareId,
                                final String labelString, final String userDataId, final String numExecutors,
                                final String jvmOptions, final String fsRoot, final boolean installPrivateKey,
                                final int overrideRetentionTime, final String keyPairName, final String networkId,
                                final String securityGroups, final String credentialsId, final JCloudsCloud.SlaveType slaveType, final String availabilityZone) {

        this.name = Util.fixEmptyAndTrim(name);
        this.imageId = Util.fixEmptyAndTrim(imageId);
        this.hardwareId = Util.fixEmptyAndTrim(hardwareId);
        this.labelString = Util.fixNull(labelString);
        this.numExecutors = Util.fixNull(numExecutors);
        this.jvmOptions = Util.fixEmptyAndTrim(jvmOptions);
        this.userDataId = userDataId;

        this.fsRoot = Util.fixEmptyAndTrim(fsRoot);
        this.installPrivateKey = installPrivateKey;
        this.overrideRetentionTime = overrideRetentionTime;
        this.keyPairName = keyPairName;
        this.networkId = networkId;
        this.securityGroups = securityGroups;
        this.credentialsId = credentialsId;
        this.slaveType = slaveType;
        this.availabilityZone = availabilityZone;

        readResolve();
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        labelSet = Label.parse(labelString);
        return this;
    }

    public String getJvmOptions() {
        if (jvmOptions == null) {
            return "";
        } else {
            return jvmOptions;
        }
    }

    public int getNumExecutors() {
        return Util.tryParseNumber(numExecutors, 1).intValue();
    }

    public String getFsRoot() {
        if (fsRoot == null || fsRoot.equals("")) {
            return "/jenkins";
        } else {
            return fsRoot;
        }
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    /*package*/ Supplier<Server> getSuplier(@Nonnull final JCloudsCloud cloud) throws Openstack.ActionFailed {
        return new Supplier<Server>() {
            @Override public Server get() {
                return JCloudsSlaveTemplate.this.provision(cloud);
            }
        };
    }

    /**
     * Provision and connect as a slave.
     *
     * @throws Openstack.ActionFailed Provisioning failed.
     */
    public @Nonnull JCloudsSlave provisionSlave(@Nonnull JCloudsCloud cloud, @Nonnull TaskListener listener) throws IOException, Openstack.ActionFailed {
        Server nodeMetadata = provision(cloud);

        try {
            return new JCloudsSlave(cloud.getDisplayName(), getFsRoot(), nodeMetadata, labelString,
                    numExecutors, overrideRetentionTime, getJvmOptions(), credentialsId, slaveType);
        } catch (Descriptor.FormException e) {
            throw new AssertionError("Invalid configuration " + e.getMessage());
        }
    }

    /**
     * Provision OpenStack machine.
     *
     * @throws Openstack.ActionFailed In case the provisioning failed.
     * @see #provisionSlave(JCloudsCloud, TaskListener)
     */
    public @Nonnull Server provision(@Nonnull JCloudsCloud cloud) throws Openstack.ActionFailed {
        final ServerCreateBuilder builder = Builders.server();
        final String nodeName = name + "-" + System.currentTimeMillis() % 1000;
        LOGGER.info("Provisioning new openstack node " + nodeName);
        // Ensure predictable node name so we can inject it into user data
        builder.name(nodeName);

        if (!Strings.isNullOrEmpty(imageId)) {
            LOGGER.fine("Setting image id to " + imageId);
            builder.image(imageId);
        }
        if (!Strings.isNullOrEmpty((hardwareId))) {
            LOGGER.fine("Setting hardware Id to " + hardwareId);
            builder.flavor(hardwareId);
        }

        if (!Strings.isNullOrEmpty(networkId)) {
            LOGGER.fine("Setting network to " + networkId);
            builder.networks(Arrays.asList(networkId));
        }

        if (!Strings.isNullOrEmpty(securityGroups)) {
            LOGGER.fine("Setting security groups to " + securityGroups);
            for (String sg: csvToArray(securityGroups)) {
                builder.addSecurityGroup(sg);
            }
        }

        if (!Strings.isNullOrEmpty(keyPairName)) {
            LOGGER.fine("Setting keyPairName to " + keyPairName);
            builder.keypairName(keyPairName);
        }

        if (!Strings.isNullOrEmpty(availabilityZone)) {
            LOGGER.fine("Setting availabilityZone to " + availabilityZone);
            builder.availabilityZone(availabilityZone);
        }

        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        UserDataConfig.UserDataConfigProvider myProvider = providers.get(UserDataConfig.UserDataConfigProvider.class);
        Config userData = myProvider.getConfigById(userDataId);
        if (userData != null && !userData.content.isEmpty()) {
            HashMap<String, String> vars = new HashMap<String, String>();
            String rootUrl = Jenkins.getInstance().getRootUrl();
            vars.put("JENKINS_URL", rootUrl);
            vars.put("SLAVE_JAR_URL", rootUrl + "jnlpJars/slave.jar");
            vars.put("SLAVE_JNLP_URL", rootUrl + "computer/" + nodeName + "/slave-agent.jnlp");
            vars.put("SLAVE_LABELS", labelString);
            String content = Util.replaceMacro(userData.content, vars);
            LOGGER.fine("Sending user-data:\n" + content);
            builder.userData(Base64.encode(content.getBytes()));
        }

        final Openstack openstack = cloud.getOpenstack();
        final Server server = openstack.bootAndWaitActive(builder, cloud.startTimeout);
        LOGGER.info("Provisioned: " + server.toString());

        if (cloud.isFloatingIps()) {
            LOGGER.fine("Assiging floating IP to " + nodeName);
            openstack.assignFloatingIp(server);
            // Make sure address information is refreshed
            return openstack.updateInfo(server);
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

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<JCloudsSlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<JCloudsSlaveTemplate> {
        private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][-a-zA-Z0-9]{0,79}");

        @Override
        public String getDisplayName() {
            return null;
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckName(@QueryParameter String value) {
            // org.jclouds.predicates.validators.DnsNameValidator
            if (NAME_PATTERN.matcher(value).find()) return FormValidation.ok();

            return FormValidation.error(
                    "Must be a lowercase string, from 1 to 80 characteres long, starting with letter or number"
            );
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckNumExecutors(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillSlaveTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("SSH", "SSH");
            items.add("JNLP", "JNLP");

            return items;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillHardwareIdItems(@QueryParameter String hardwareId,
                                                  @RelativePath("..") @QueryParameter String endPointUrl,
                                                  @RelativePath("..") @QueryParameter String identity,
                                                  @RelativePath("..") @QueryParameter String credential,
                                                  @RelativePath("..") @QueryParameter String zone
        ) {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            try {
                final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
                for (Flavor flavor : openstack.getSortedFlavors()) {
                    m.add(String.format("%s (%s)", flavor.getName(), flavor.getId()), flavor.getId());
                }
                return m;
            } catch (AuthenticationException _) {
                // Incorrect credentials - noop
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            }

            if (Util.fixEmpty(hardwareId) != null) {
                m.add(hardwareId);
            }

            return m;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillImageIdItems(@QueryParameter String imageId,
                                               @RelativePath("..") @QueryParameter String endPointUrl,
                                               @RelativePath("..") @QueryParameter String identity,
                                               @RelativePath("..") @QueryParameter String credential,
                                               @RelativePath("..") @QueryParameter String zone
        ) {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            try {
                final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
                for (Image image : openstack.getSortedImages()) {
                    m.add(String.format("%s (%s)", image.getName(), image.getId()), image.getId());
                }
                return m;
            } catch (AuthenticationException _) {
                // Incorrect credentials - noop
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            }

            if (Util.fixEmpty(imageId) != null) {
                m.add(imageId);
            }

            return m;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillNetworkIdItems(@QueryParameter String networkId,
                                                 @RelativePath("..") @QueryParameter String endPointUrl,
                                                 @RelativePath("..") @QueryParameter String identity,
                                                 @RelativePath("..") @QueryParameter String credential,
                                                 @RelativePath("..") @QueryParameter String zone
        ) {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            try {
                Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
                for (org.openstack4j.model.network.Network network: openstack.getSortedNetworks()) {
                    m.add(String.format("%s (%s)", network.getName(), network.getId()), network.getId());
                }
                return m;
            } catch (AuthenticationException _) {
                // Incorrect credentials - noop
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            }

            if (Util.fixEmpty(networkId) != null) {
                m.add(networkId);
            }

            return m;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
                return new ListBoxModel();
            }
            return new StandardUsernameListBoxModel().withMatching(SSHAuthenticator.matcher(Connection.class),
                    CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context,
                            ACL.SYSTEM, SSHLauncher.SSH_SCHEME));
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckOverrideRetentionTime(@QueryParameter String value) {
            try {if (Integer.parseInt(value) == -1) {return FormValidation.ok();}
                } catch (NumberFormatException e) {}
            return FormValidation.validateNonNegativeInteger(value);
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillUserDataIdItems() {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            ConfigProvider provider = getConfigProvider();
            for(Config config : provider.getAllConfigs()) {
                m.add(config.name, config.id);
            }

            return m;
        }

        private ConfigProvider getConfigProvider() {
            ExtensionList<ConfigProvider> providers = ConfigProvider.all();
            return providers.get(UserDataConfig.UserDataConfigProvider.class);
        }
    }
}
